// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import android.util.Log

private const val TAG = "OpportunisticSync"

class OpportunisticSync(
    private val evidenceReader: EvidenceReader,
    private val syncEnqueue: SyncEnqueue,
    private val networkAvailability: NetworkAvailability,
    private val emptySpoolRecovery: (() -> Unit)? = null,
    private val onNetworkRecovery: (() -> Unit)? = null,
    private val failureReporter: (String, Throwable) -> Unit = { message, throwable ->
        runCatching { Log.w(TAG, message, throwable) }
    },
) {
    private val lock = Any()
    private var started = false
    private var lastEnqueuedPending: Int? = null
    private var networkRecovered = false
    private var _lastError: String? = null
    @Volatile var diagnosticReporter: ((String, Throwable) -> Unit)? = null

    val lastError: String?
        get() = synchronized(lock) { _lastError }

    fun isDegraded(): Boolean = synchronized(lock) { _lastError != null }

    fun start() {
        synchronized(lock) {
            if (started) return
            try {
                networkAvailability.start(
                    onUsableNetwork = { onUsableNetwork() },
                    onNetworkLost = { onNetworkLost() },
                )
                started = true
                _lastError = null
            } catch (e: Exception) {
                started = false
                recordErrorLocked("network registration failed", e)
            }
        }
    }

    fun stop() {
        // Stop can come from a notification receiver on the main thread. WorkManager accepts
        // this request there; a Room pending-count query does not. An empty request may still
        // make a journal status probe, but it cannot ingest absent material.
        syncEnqueue.enqueueNow()
        synchronized(lock) {
            if (!started) return
            runCatching { networkAvailability.stop() }
                .onFailure { recordErrorLocked("network unregister failed", it) }
            started = false
            lastEnqueuedPending = null
            networkRecovered = false
        }
    }

    fun onPairingSuccess() {
        syncEnqueue.enqueueNow()
    }

    fun onNetworkLost() {
        synchronized(lock) {
            lastEnqueuedPending = null
            networkRecovered = true
        }
    }

    fun onUsableNetwork(runEmptySpoolRecovery: Boolean = true) {
        val pending = pendingCountOrNull() ?: return
        if (pending == 0) {
            synchronized(lock) {
                lastEnqueuedPending = null
                networkRecovered = false
            }
            if (runEmptySpoolRecovery) {
                emptySpoolRecovery?.invoke()
            }
            return
        }
        val (shouldEnqueue, recovered) = synchronized(lock) {
            if (pending != lastEnqueuedPending) {
                val wasRecovered = networkRecovered
                lastEnqueuedPending = pending
                networkRecovered = false
                true to wasRecovered
            } else {
                false to false
            }
        }
        if (shouldEnqueue) {
            if (recovered) {
                runCatching { onNetworkRecovery?.invoke() }
            }
            syncEnqueue.enqueueNow()
        }
    }

    fun enqueueIfPending() {
        val pending = pendingCountOrNull() ?: return
        if (pending > 0) {
            syncEnqueue.enqueueNow()
        }
    }

    private fun pendingCountOrNull(): Int? =
        try {
            evidenceReader.pendingCount()
        } catch (e: Exception) {
            synchronized(lock) { recordErrorLocked("pending inspection failed", e) }
            null
        }

    private fun recordErrorLocked(message: String, throwable: Throwable) {
        _lastError = "$message: ${throwable.javaClass.simpleName}"
        failureReporter(message, throwable)
        runCatching { diagnosticReporter?.invoke(message, throwable) }
    }
}
