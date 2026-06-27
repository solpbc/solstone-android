// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.observer.harness.HarnessPairProbeResult
import app.solstone.observer.harness.PairConnectionMode

enum class PhotoPairOutcome { IGNORED, PAIRED, ALREADY_CONNECTED, RECONNECTING, RETRY, FAILED }

fun decidePhotoPair(
    decoded: String?,
    looksLikePairLink: (String) -> Boolean,
    onScannedPairLink: (String) -> HarnessPairProbeResult?,
): PhotoPairOutcome {
    if (decoded == null) return PhotoPairOutcome.IGNORED
    if (!looksLikePairLink(decoded)) return PhotoPairOutcome.IGNORED
    return try {
        when (onScannedPairLink(decoded)?.connectionMode) {
            PairConnectionMode.PAIRING -> PhotoPairOutcome.PAIRED
            PairConnectionMode.ALREADY_CONNECTED -> PhotoPairOutcome.ALREADY_CONNECTED
            PairConnectionMode.RECONNECTING -> PhotoPairOutcome.RECONNECTING
            null -> PhotoPairOutcome.RETRY
        }
    } catch (_: Exception) {
        PhotoPairOutcome.FAILED
    }
}
