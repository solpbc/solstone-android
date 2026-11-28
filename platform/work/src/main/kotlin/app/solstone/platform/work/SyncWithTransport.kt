// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.BundleFile
import app.solstone.core.observer.ObserverIngestClient
import app.solstone.core.observer.SegmentReconciler
import app.solstone.platform.persistence.room.ConfirmedCopyFinisher
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.pl.transport.conscrypt.RelayWebSocketClosedException
import java.io.IOException
import java.io.InputStream
import java.io.Closeable
import java.io.File
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.CalendarCapabilityRead
import app.solstone.core.pl.JournalAttempt
import app.solstone.core.pl.LegOutcome
import app.solstone.core.pl.fetchCalendarCapability
import app.solstone.core.pl.journalRefusalIn
import app.solstone.core.pl.unknownJournalIn
import app.solstone.core.sources.CALENDAR_STREAM
import app.solstone.core.sources.OBSIDIAN_STREAM
import app.solstone.core.sources.SCREENSHOTS_STREAM
import app.solstone.core.sources.DELIVERY_STREAMS
import app.solstone.platform.folders.FolderStageResult

internal fun <C> syncWithTransport(
    transport: SyncTransport,
    openClient: (SyncTransport) -> C,
    store: DrainStore,
    openPayload: (SegmentRow, BundleFile) -> InputStream,
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
    onCalendarCapability: ((Boolean) -> Unit)? = null,
    prepareObsidian: ((PlHttpClient, () -> Boolean) -> Boolean)? = null,
    obsidianCurrent: () -> Boolean = { true },
    prepareScreenshots: ((PlHttpClient, () -> Boolean) -> Boolean)? = null,
    foldersStage: ((PlHttpClient, () -> Boolean) -> FolderStageResult)? = null,
    onImportOnlyRetry: () -> Unit = {},
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
                val dueNow = selectDrainSegments(store.segmentsForDrain(), now(), homeInstanceId)
                val postCalendar = if (dueNow.none { it.stream == CALENDAR_STREAM }) {
                    false
                } else {
                    val read = try {
                        fetchCalendarCapability(c, legs.pairing.clientCertFingerprint)
                    } catch (e: RelayWebSocketClosedException) {
                        throw e
                    } catch (_: Throwable) {
                        CalendarCapabilityRead.Failed
                    }
                    when (read) {
                        is CalendarCapabilityRead.Parsed -> {
                            onCalendarCapability?.invoke(read.calendarNotes)
                            read.calendarNotes
                        }
                        CalendarCapabilityRead.Failed -> false
                    }
                }
                val postObsidian = if (dueNow.none { it.stream == OBSIDIAN_STREAM }) false else {
                    try { prepareObsidian?.invoke(c, pairingCurrent) == true }
                    catch (e: RelayWebSocketClosedException) { throw e }
                    catch (e: Exception) { log("Obsidian delivery check failed", e); false }
                }
                val postScreenshots = if (dueNow.none { it.stream == SCREENSHOTS_STREAM }) false else {
                    try { prepareScreenshots?.invoke(c, pairingCurrent) == true }
                    catch (e: RelayWebSocketClosedException) { throw e }
                    catch (e: Exception) { log("Screenshots delivery check failed", e); false }
                }
                val report = drainSegments(
                    store = store,
                    reconcile = SegmentReconciler(c)::diff,
                    ingest = { manifest, fileBytes ->
                        ObserverIngestClient(c) { "solstoneSync${System.nanoTime()}" }.ingest(
                            manifest = manifest,
                            openFile = fileBytes,
                            host = host,
                            platform = "android",
                        )
                    },
                    openPayload = openPayload,
                    now = now,
                    log = log,
                    finisher = finisher,
                    readStoredZone = { segment -> spoolDir?.let { readStoredZoneFor(it, segment) } },
                    pairingCurrent = pairingCurrent,
                    homeInstanceId = homeInstanceId,
                    postCalendar = postCalendar,
                    postObsidian = postObsidian,
                    postScreenshots = postScreenshots,
                    obsidianCurrent = obsidianCurrent,
                )
                val foldersResult = foldersStage?.invoke(c, pairingCurrent)
                when (foldersResult) {
                    FolderStageResult.AUTH_STOP -> {
                        onRefused()
                        return@use SyncOutcome.FAILURE
                    }
                    FolderStageResult.PAIRING_LEFT -> return@use SyncOutcome.RETRY
                    else -> Unit
                }
                if (report.halted) onRefused()
                if ((imports == ImportStageResult.RETRY || foldersResult == FolderStageResult.RETRY) &&
                    report.workOutcome == SyncOutcome.SUCCESS
                ) {
                    if (imports == ImportStageResult.RETRY && foldersResult != FolderStageResult.RETRY) {
                        onImportOnlyRetry()
                    }
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
            val message = if (attempt.refusal == null) "journal connection interrupted" else "journal refused this device"
            log("$message $stage; retry", failure)
            SyncOutcome.RETRY
        }
        LegOutcome.STOPPED -> {
            log("journal stopped accepting this device $stage", failure)
            SyncOutcome.FAILURE
        }
    }
}
