// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.diagnostics.PairingFact
import app.solstone.observer.harness.HarnessPairProbeResult

data class ImageRef(val id: Long, val dateAddedSeconds: Long)

data class PhotoPairSeams(
    val recentImageCandidates: () -> List<ImageRef>,
    val decode: (ImageRef) -> String?,
    val pairingFact: () -> PairingFact,
    val onScannedPairLink: (String) -> HarnessPairProbeResult?,
    val looksLikePairLink: (String) -> Boolean,
    val unregisterWatcher: () -> Unit,
    val onPairingReadyCue: () -> Unit,
    val onPairingFailedCue: () -> Unit,
    val log: (String) -> Unit,
    val nowSeconds: () -> Long,
)

/**
 * Threading contract: every entry point is invoked on the glasses single background executor.
 * State is plain non-atomic state and must only be mutated on that executor. The Android adapter
 * must dispatch to background.execute before calling onStartup() or onChange().
 */
class PhotoPairCoordinator(private val seams: PhotoPairSeams) {
    private var scanInFlight = false
    private var rescanRequested = false
    private val attemptedLinks = mutableSetOf<String>()

    fun onStartup() {
        if (seams.pairingFact() == PairingFact.PAIRED) {
            seams.unregisterWatcher()
            return
        }
        scan()
    }

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
        val candidates = seams.recentImageCandidates()
            .take(MAX_CANDIDATES)
            .filter { it.dateAddedSeconds >= cutoffSeconds }
        for (candidate in candidates) {
            val decoded = seams.decode(candidate)
            if (decoded == null) {
                seams.log("photo id=${candidate.id} decoded=null")
                continue
            }
            if (decoded in attemptedLinks) continue
            if (seams.looksLikePairLink(decoded)) {
                seams.onPairingReadyCue()
            }
            when (decidePhotoPair(decoded, seams.looksLikePairLink, seams.onScannedPairLink)) {
                PhotoPairOutcome.PAIRED,
                PhotoPairOutcome.ALREADY_CONNECTED,
                -> {
                    attemptedLinks += decoded
                    seams.unregisterWatcher()
                    return
                }
                PhotoPairOutcome.RECONNECTING -> {
                    attemptedLinks += decoded
                }
                PhotoPairOutcome.FAILED -> {
                    attemptedLinks += decoded
                    seams.onPairingFailedCue()
                }
                PhotoPairOutcome.IGNORED -> {
                    attemptedLinks += decoded
                }
                PhotoPairOutcome.RETRY -> return
            }
        }
    }

    companion object {
        const val MAX_CANDIDATES = 20
        const val MAX_AGE_SECONDS = 24 * 60 * 60L
    }
}
