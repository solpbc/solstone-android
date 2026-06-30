// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.observer.harness.PairConnectionMode
import app.solstone.observer.harness.PairAttemptOutcome

enum class PhotoPairOutcome { PAIRED, ALREADY_CONNECTED, RECONNECTING, RETRY, NETWORK_UNAVAILABLE, REFRESH_CODE, FAILED }

fun decidePhotoPair(outcome: PairAttemptOutcome): PhotoPairOutcome =
    when (outcome) {
        is PairAttemptOutcome.Linked -> when (outcome.result.connectionMode) {
            PairConnectionMode.PAIRING -> PhotoPairOutcome.PAIRED
            PairConnectionMode.ALREADY_CONNECTED -> PhotoPairOutcome.ALREADY_CONNECTED
            PairConnectionMode.RECONNECTING -> PhotoPairOutcome.RECONNECTING
        }
        PairAttemptOutcome.Retry -> PhotoPairOutcome.RETRY
        PairAttemptOutcome.NetworkUnavailable -> PhotoPairOutcome.NETWORK_UNAVAILABLE
        is PairAttemptOutcome.WindowClosed -> PhotoPairOutcome.REFRESH_CODE
        is PairAttemptOutcome.OtherFailure -> PhotoPairOutcome.FAILED
    }
