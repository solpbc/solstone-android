// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.BundleFile
import app.solstone.core.observer.ObserverIngestClient
import app.solstone.core.observer.SegmentReconciler
import app.solstone.core.pl.BeaconState
import app.solstone.core.pl.PlHttpClient
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.pl.transport.conscrypt.RelayWebSocketClosedException
import java.io.Closeable
import java.io.IOException

internal fun <C> syncWithTransport(
    transport: SyncTransport,
    openClient: (SyncTransport) -> C,
    store: DrainStore,
    readPayload: (SegmentRow, BundleFile) -> ByteArray,
    existingHandle: String?,
    loadBeaconState: () -> BeaconState?,
    persistBeaconState: (BeaconState) -> Unit,
    host: String,
    version: String,
    streamType: String,
    now: () -> Long,
    log: (String, Throwable?) -> Unit,
): SyncOutcome where C : PlHttpClient, C : Closeable =
    openClient(transport).use { client ->
        val status = try {
            client.request("GET", "/app/network/api/status", emptyMap(), ByteArray(0)).status
        } catch (e: RelayWebSocketClosedException) {
            throw e
        } catch (e: IOException) {
            log("status probe io; retry", e)
            return@use SyncOutcome.RETRY
        }
        when (decideReachability(paired = true, reachable = status == 200)) {
            ReachabilityVerdict.SKIP -> SyncOutcome.FAILURE
            ReachabilityVerdict.RESCHEDULE -> SyncOutcome.RETRY
            ReachabilityVerdict.DRAIN -> {
                val report = drainSegments(
                    store = store,
                    reconcile = SegmentReconciler(client)::diff,
                    ingest = { manifest, fileBytes ->
                        ObserverIngestClient(client) { "solstoneSync${System.nanoTime()}" }.ingest(
                            manifest = manifest,
                            fileBytes = fileBytes,
                            host = host,
                            platform = "android",
                        )
                    },
                    readPayload = readPayload,
                    now = now,
                    log = log,
                )
                if (existingHandle != null) {
                    val emit = emitObserverHealth(
                        client = client,
                        priorState = loadBeaconState(),
                        persist = persistBeaconState,
                        streamType = streamType,
                        handle = existingHandle,
                        version = version,
                        now = now(),
                        syncRow = store.syncState(),
                        cleanDrain = report.cleanDrain,
                        failedThisRun = report.failedThisRun,
                        rawErrorReason = report.lastErrorReason,
                        log = log,
                    )
                    if (emit == BeaconEmitResult.FAILED) {
                        log("observer health beacon not delivered", null)
                    }
                }
                report.workOutcome
            }
        }
    }
