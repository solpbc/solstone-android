// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.BundleFile
import app.solstone.core.observer.ObserverIngestClient
import app.solstone.core.observer.SegmentReconciler
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
    host: String,
    now: () -> Long,
    log: (String, Throwable?) -> Unit,
    onUsableConnection: (() -> Unit)? = null,
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
        if (status == 200) {
            onUsableConnection?.invoke()
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
                report.workOutcome
            }
        }
    }
