// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import app.solstone.observer.harness.PairAttemptOutcome

fun pairStatusText(outcome: PairAttemptOutcome): String =
    when (outcome) {
        is PairAttemptOutcome.Linked ->
            if (outcome.result.pairStatus in 200..299 && outcome.result.statusStatus in 200..299) {
                "Paired"
            } else {
                "Pairing failed"
            }
        PairAttemptOutcome.Retry -> "Scanning"
        PairAttemptOutcome.NetworkUnavailable -> "Network unavailable"
        is PairAttemptOutcome.WindowClosed -> "Pairing code expired"
        is PairAttemptOutcome.OtherFailure -> "Pairing failed"
    }

fun PairAttemptOutcome.isSuccessfulPair(): Boolean =
    this is PairAttemptOutcome.Linked &&
        result.pairStatus in 200..299 &&
        result.statusStatus in 200..299
