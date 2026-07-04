// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.diagnostics.PairingFact
import app.solstone.observer.harness.PairAttemptOutcome

data class ImageRef(val id: Long, val dateAddedSeconds: Long)

data class PhotoPairSeams(
    val recentImageCandidates: () -> List<ImageRef>,
    val appStartSeconds: () -> Long,
    val decode: (ImageRef) -> String?,
    val pairingFact: () -> PairingFact,
    val onScannedPairLink: (String) -> PairAttemptOutcome,
    val looksLikePairLink: (String) -> Boolean,
    val unregisterWatcher: () -> Unit,
    val onPairingStartedCue: () -> Unit,
    val onNetworkUnavailableCue: () -> Unit,
    val onRefreshCodeCue: () -> Unit,
    val onReconnectingCue: () -> Unit,
    val onRetryCue: () -> Unit,
    val onPairingFailedCue: () -> Unit,
    val log: (String) -> Unit,
    val isUsableNetworkPresent: () -> Boolean,
    val nowSeconds: () -> Long,
)

/**
 * Threading contract: every entry point is invoked on the glasses single background executor.
 * State is plain non-atomic state and must only be mutated on that executor. The Android adapter
 * must dispatch to background.execute before calling onChange().
 */
class PhotoPairCoordinator(private val seams: PhotoPairSeams) {
    private var scanInFlight = false
    private var rescanRequested = false
    private val attemptedLinks = mutableSetOf<String>()
    private val networkUnavailableAnnounced = mutableSetOf<String>()
    private val reconnectingAnnounced = mutableSetOf<String>()
    private val retryAnnounced = mutableSetOf<String>()

    fun onChange() {
        scan()
    }

    private fun scan() {
        if (scanInFlight) {
            rescanRequested = true
            return
        }
        scanInFlight = true
        try {
            runOneScan()
            if (rescanRequested) {
                rescanRequested = false
                runOneScan()
            }
        } finally {
            scanInFlight = false
            rescanRequested = false
        }
    }

    private fun runOneScan() {
        val cutoffSeconds = seams.nowSeconds() - MAX_AGE_SECONDS
        val appStart = seams.appStartSeconds()
        val candidates = seams.recentImageCandidates()
            .take(MAX_CANDIDATES)
            .filter { it.dateAddedSeconds >= cutoffSeconds && it.dateAddedSeconds > appStart }
        for (candidate in candidates) {
            val decoded = seams.decode(candidate)
            if (decoded == null) {
                seams.log("photo id=${candidate.id} decoded=null")
                continue
            }
            if (decoded in attemptedLinks) continue
            if (!seams.looksLikePairLink(decoded)) {
                attemptedLinks += decoded
                continue
            }
            if (!seams.isUsableNetworkPresent()) {
                reconnectingAnnounced -= decoded
                retryAnnounced -= decoded
                if (decoded !in networkUnavailableAnnounced) {
                    seams.onNetworkUnavailableCue()
                    networkUnavailableAnnounced += decoded
                }
                continue
            }
            networkUnavailableAnnounced.clear()
            seams.onPairingStartedCue()
            val outcome = seams.onScannedPairLink(decoded)
            logOutcome(outcome)
            when (decidePhotoPair(outcome)) {
                PhotoPairOutcome.PAIRED,
                PhotoPairOutcome.ALREADY_CONNECTED,
                -> {
                    attemptedLinks += decoded
                    seams.unregisterWatcher()
                    return
                }
                PhotoPairOutcome.RECONNECTING -> {
                    networkUnavailableAnnounced -= decoded
                    retryAnnounced -= decoded
                    if (decoded !in reconnectingAnnounced) {
                        seams.onReconnectingCue()
                        reconnectingAnnounced += decoded
                    }
                }
                PhotoPairOutcome.NETWORK_UNAVAILABLE -> {
                    reconnectingAnnounced -= decoded
                    retryAnnounced -= decoded
                    if (decoded !in networkUnavailableAnnounced) {
                        seams.onNetworkUnavailableCue()
                        networkUnavailableAnnounced += decoded
                    }
                }
                PhotoPairOutcome.REFRESH_CODE -> {
                    reconnectingAnnounced -= decoded
                    retryAnnounced -= decoded
                    attemptedLinks += decoded
                    seams.onRefreshCodeCue()
                }
                PhotoPairOutcome.FAILED -> {
                    reconnectingAnnounced -= decoded
                    retryAnnounced -= decoded
                    attemptedLinks += decoded
                    seams.onPairingFailedCue()
                }
                PhotoPairOutcome.RETRY -> {
                    networkUnavailableAnnounced -= decoded
                    reconnectingAnnounced -= decoded
                    if (decoded !in retryAnnounced) {
                        seams.onRetryCue()
                        retryAnnounced += decoded
                    }
                    return
                }
            }
        }
    }

    private fun logOutcome(outcome: PairAttemptOutcome) {
        when (outcome) {
            is PairAttemptOutcome.Linked -> seams.log("relay-pair outcome=Linked mode=${outcome.result.connectionMode}")
            PairAttemptOutcome.NetworkUnavailable -> seams.log("relay-pair outcome=NetworkUnavailable")
            is PairAttemptOutcome.WindowClosed -> seams.log("relay-pair outcome=WindowClosed status=${outcome.statusCode}")
            is PairAttemptOutcome.OtherFailure -> {
                seams.log("relay-pair outcome=OtherFailure type=${outcome.exceptionType} status=${outcome.statusCode}")
            }
            PairAttemptOutcome.Retry -> seams.log("relay-pair outcome=Retry")
        }
    }

    companion object {
        const val MAX_CANDIDATES = 20
        const val MAX_AGE_SECONDS = 24 * 60 * 60L
    }
}
