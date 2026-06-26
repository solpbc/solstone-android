// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.observer.harness.HarnessPairProbeResult

enum class PhotoPairOutcome { IGNORED, PAIRED, RETRY, FAILED }

fun decidePhotoPair(
    decoded: String?,
    looksLikePairLink: (String) -> Boolean,
    onScannedPairLink: (String) -> HarnessPairProbeResult?,
): PhotoPairOutcome {
    if (decoded == null) return PhotoPairOutcome.IGNORED
    if (!looksLikePairLink(decoded)) return PhotoPairOutcome.IGNORED
    return try {
        if (onScannedPairLink(decoded) == null) {
            PhotoPairOutcome.RETRY
        } else {
            PhotoPairOutcome.PAIRED
        }
    } catch (_: Exception) {
        PhotoPairOutcome.FAILED
    }
}
