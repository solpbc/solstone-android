// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import android.util.Log

private const val TAG = "OpportunisticSync"

class OpportunisticSync(
    private val evidenceReader: EvidenceReader,
    private val syncEnqueue: SyncEnqueue,
    private val networkAvailability: NetworkAvailability,
) {
    private val lock = Any()
    private var started = false
    private var lastEnqueuedPending: Int? = null
    private var _lastError: String? = null

    val lastError: String?
        get() = synchronized(lock) { _lastError }

    fun isDegraded(): Boolean = synchronized(lock) { _lastError != null }

    fun start() {
        synchronized(lock) {
            if (started) return
            try {
                networkAvailability.start { onUsableNetwork() }
                started = true
                _lastError = null
            } catch (e: Exception) {
                started = false
                recordErrorLocked("network registration failed", e)
            }
        }
    }

    fun stop() {
        enqueueIfPending()
        synchronized(lock) {
            if (!started) return
            runCatching { networkAvailability.stop() }
                .onFailure { recordErrorLocked("network unregister failed", it) }
            started = false
        }
    }

    fun onPairingSuccess() {
        enqueueIfPending()
    }

    fun onUsableNetwork() {
        val pending = pendingCountOrNull() ?: return
        val shouldEnqueue = synchronized(lock) {
            if (pending == 0) {
                lastEnqueuedPending = null
                false
            } else if (pending != lastEnqueuedPending) {
                lastEnqueuedPending = pending
                true
            } else {
                false
            }
        }
        if (shouldEnqueue) {
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
        runCatching { Log.w(TAG, message, throwable) }
    }
}
