// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.BundleFile
import app.solstone.core.observer.ObserverIngestClient
import app.solstone.core.observer.SegmentReconciler
import app.solstone.core.pl.PlHttpClient
import app.solstone.platform.persistence.room.ConfirmedCopyFinisher
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.pl.transport.conscrypt.RelayWebSocketClosedException
import java.io.Closeable
import java.io.File
import java.io.IOException
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.pl.JournalAttempt
import app.solstone.core.pl.LegOutcome
import app.solstone.core.pl.journalRefusalIn
import app.solstone.core.pl.unknownJournalIn

internal fun <C> syncWithTransport(
    transport: SyncTransport,
    openClient: (SyncTransport) -> C,
    store: DrainStore,
    readPayload: (SegmentRow, BundleFile) -> ByteArray,
    host: String,
    now: () -> Long,
    log: (String, Throwable?) -> Unit,
    finisher: ConfirmedCopyFinisher,
    onUsableConnection: ((PlHttpClient) -> Unit)? = null,
    spoolDir: File? = null,
    allowsOwnerMaterial: Boolean,
    pairingCurrent: () -> Boolean = { true },
    homeInstanceId: String? = null,
    onRefused: () -> Unit = {},
    onAuthorized: () -> Unit = {},
    importStage: ((PlHttpClient, () -> Boolean) -> ImportStageResult)? = null,
    attempt: JournalAttempt? = null,
): SyncOutcome where C : PlHttpClient, C : Closeable {
    val legs = attempt ?: JournalAttempt.untracked(PairingGeneration("", ""))
    val client = try {
        openClient(transport)
    } catch (e: RelayWebSocketClosedException) {
        throw e
    } catch (t: Throwable) {
        return legFailure(legs, t, "opening client", log)
    }
    return client.use { c ->
        val status = try {
            c.request("GET", "/app/network/api/status", emptyMap(), ByteArray(0)).status
        } catch (e: RelayWebSocketClosedException) {
            throw e
        } catch (t: Throwable) {
            return@use legFailure(legs, t, "on status probe", log)
        }
        // Any HTTP answer is the journal's acceptance at the TLS layer, 401 and 403 included.
        legs.answered()
        // The journal answered: 200 says this pairing is accepted, 401/403 that it is refused.
        // Any other answer, or none, says nothing about the pairing either way.
        when (status) {
            200 -> onAuthorized()
            401, 403 -> onRefused()
        }
        if (status == 200) {
            onUsableConnection?.invoke(client)
        }
        when (decideReachability(paired = true, reachable = status == 200)) {
            ReachabilityVerdict.SKIP -> SyncOutcome.FAILURE
            ReachabilityVerdict.RESCHEDULE -> SyncOutcome.RETRY
            ReachabilityVerdict.DRAIN -> {
                if (!allowsOwnerMaterial) {
                    return@use SyncOutcome.SUCCESS
                }
                // Imports go first: the owner shared them on purpose, and there are few.
                val imports = importStage?.invoke(c, pairingCurrent)
                when (imports) {
                    ImportStageResult.AUTH_STOP -> {
                        onRefused()
                        return@use SyncOutcome.FAILURE
                    }
                    ImportStageResult.PAIRING_LEFT -> return@use SyncOutcome.RETRY
                    else -> Unit
                }
                val report = drainSegments(
                    store = store,
                    reconcile = SegmentReconciler(c)::diff,
                    ingest = { manifest, fileBytes ->
                        ObserverIngestClient(c) { "solstoneSync${System.nanoTime()}" }.ingest(
                            manifest = manifest,
                            fileBytes = fileBytes,
                            host = host,
                            platform = "android",
                        )
                    },
                    readPayload = readPayload,
                    now = now,
                    log = log,
                    finisher = finisher,
                    readStoredZone = { segment -> spoolDir?.let { readStoredZoneFor(it, segment) } },
                    pairingCurrent = pairingCurrent,
                    homeInstanceId = homeInstanceId,
                )
                if (report.halted) onRefused()
                if (imports == ImportStageResult.RETRY && report.workOutcome == SyncOutcome.SUCCESS) {
                    SyncOutcome.RETRY
                } else {
                    report.workOutcome
                }
            }
        }
    }
}

/**
 * What one leg's failure means for the sync run. Only the transport's typed findings are a refusal
 * or an unknown journal; any other I/O failure, TLS ones included, is unreachable. The device is
 * marked refused only when the refusal stopped the credential, which the attempt's guard records.
 */
private fun legFailure(
    attempt: JournalAttempt,
    failure: Throwable,
    stage: String,
    log: (String, Throwable?) -> Unit,
): SyncOutcome {
    val classified = journalRefusalIn(failure) != null || unknownJournalIn(failure) != null
    if (!classified && failure !is IOException) {
        log("fatal error $stage", failure)
        return SyncOutcome.FAILURE
    }
    return when (attempt.legFailed(failure)) {
        LegOutcome.NEXT_LEG -> {
            log("journal not reached $stage; retry", failure)
            SyncOutcome.RETRY
        }
        LegOutcome.DECIDED -> {
            log("journal refused this device $stage; retry", failure)
            SyncOutcome.RETRY
        }
        LegOutcome.STOPPED -> {
            log("journal stopped accepting this device $stage", failure)
            SyncOutcome.FAILURE
        }
    }
}
