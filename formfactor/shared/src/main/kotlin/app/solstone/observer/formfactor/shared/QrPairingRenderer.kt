// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import app.solstone.observer.harness.PairAttemptOutcome
import app.solstone.observer.harness.ConnectivityFailure
import app.solstone.observer.harness.PairRoute
import app.solstone.observer.harness.PairLinkDispatchResult

fun pairLinkDispatchText(result: PairLinkDispatchResult): String? =
    when (result) {
        PairLinkDispatchResult.NoLink -> null
        PairLinkDispatchResult.InvalidLink -> "Invalid pair link"
        PairLinkDispatchResult.Busy -> "Pairing already in progress. Try again."
        is PairLinkDispatchResult.Attempted -> pairStatusText(result.outcome)
    }

fun pairStatusText(outcome: PairAttemptOutcome): String =
    when (outcome) {
        is PairAttemptOutcome.Linked ->
            if (outcome.result.pairStatus in 200..299 && outcome.result.statusStatus in 200..299) {
                "Paired"
            } else {
                "Pairing failed"
            }
        PairAttemptOutcome.Retry -> "Scanning"
        is PairAttemptOutcome.NetworkUnavailable -> networkFailureText(outcome)
        is PairAttemptOutcome.WindowClosed -> "Pairing code expired"
        is PairAttemptOutcome.OtherFailure -> "Pairing failed"
    }

private fun networkFailureText(outcome: PairAttemptOutcome.NetworkUnavailable): String =
    when (outcome.failure) {
        ConnectivityFailure.DEVICE_OFFLINE -> "No network connection"
        ConnectivityFailure.NAME_RESOLUTION -> "Couldn't find ${outcome.endpointHost}. Check the address."
        ConnectivityFailure.HOST_DID_NOT_ANSWER -> when (outcome.route) {
            PairRoute.RELAY -> "Your journal at ${outcome.endpointHost}:${outcome.endpointPort} didn't answer."
            PairRoute.DIRECT ->
                "Your journal at ${outcome.endpointHost}:${outcome.endpointPort} didn't answer. " +
                    "Check it's running and that port ${outcome.endpointPort} isn't blocked by a firewall."
        }
    }

fun PairAttemptOutcome.isSuccessfulPair(): Boolean =
    this is PairAttemptOutcome.Linked &&
        result.pairStatus in 200..299 &&
        result.statusStatus in 200..299
