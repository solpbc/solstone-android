// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import app.solstone.observer.harness.PairAttemptOutcome
import app.solstone.observer.harness.ConnectivityFailure
import app.solstone.observer.harness.PairRoute
import app.solstone.observer.harness.PairLinkDispatchResult

/**
 * Every word the owner reads while pairing.
 *
 * ⚠ **The register here is the one the other platform already ships**, not a second one derived
 * from the same intent. `Pairing failed` · `No network connection` · `Pairing code expired` were
 * sentence-capitalised fragments that named the failure and stopped — they told the owner something
 * broke and left them holding it. The locked set says what happened, in lowercase, and then says
 * what to do next; matching it is the whole point, because an owner with a phone and a laptop
 * should not be reading two different vocabularies for one event.
 *
 * ⛔ **Do not add a message here that has no next action.** A pairing failure the owner cannot act
 * on is a diagnosis, and a diagnosis without a step is the shape this file was converged out of.
 */
private const val PAIR_GENERIC =
    "pairing didn't go through. show a new pairing code on your journal and try again."

private const val PAIR_CODE_EXPIRED =
    "the pairing window closed. show a new pairing code on your journal, then try again."

/**
 * ⚠ Fallback for a switchboard that could not even run, so nothing was learned about why.
 * Deliberately the same words as [PAIR_GENERIC]: a caller with no information owes the owner the
 * same next step, and inventing a distinct message would imply a distinction we cannot support.
 */
const val PAIR_DISPATCH_FAILED = PAIR_GENERIC

fun pairLinkDispatchText(result: PairLinkDispatchResult): String? =
    when (result) {
        PairLinkDispatchResult.NoLink -> null
        PairLinkDispatchResult.InvalidLink ->
            "that pairing link isn't one the solstone app can read. show a new pairing code on " +
                "your journal and try again."
        PairLinkDispatchResult.Busy -> "already pairing. give it a moment."
        is PairLinkDispatchResult.Attempted -> pairStatusText(result.outcome)
    }

fun pairStatusText(outcome: PairAttemptOutcome): String =
    when (outcome) {
        is PairAttemptOutcome.Linked ->
            if (outcome.result.pairStatus in 200..299 && outcome.result.statusStatus in 200..299) {
                "paired"
            } else {
                PAIR_GENERIC
            }
        PairAttemptOutcome.Retry -> "scanning"
        is PairAttemptOutcome.NetworkUnavailable -> networkFailureText(outcome)
        // ⚠ 410 and every other status collapsed into one message. Both mean the code is no longer
        // good and both need the same next step, so splitting them told the owner there was a
        // difference and then declined to say what it was.
        is PairAttemptOutcome.WindowClosed -> PAIR_CODE_EXPIRED
        is PairAttemptOutcome.OtherFailure -> PAIR_GENERIC
    }

private fun networkFailureText(outcome: PairAttemptOutcome.NetworkUnavailable): String =
    when (outcome.failure) {
        ConnectivityFailure.DEVICE_OFFLINE ->
            "this device isn't on a network. pairing needs to reach your journal directly, so " +
                "join the same wi-fi as your journal and try again. everything the solstone app " +
                "has taken in is on this device and syncs once you reconnect."
        ConnectivityFailure.NAME_RESOLUTION ->
            "couldn't find your journal at ${outcome.endpointHost}. check the address, then try " +
                "again with a new pairing code."
        ConnectivityFailure.HOST_DID_NOT_ANSWER -> when (outcome.route) {
            PairRoute.RELAY ->
                "couldn't reach your journal at ${outcome.endpointHost}:${outcome.endpointPort}. " +
                    "make sure it's running, then try again."
            PairRoute.DIRECT ->
                if (outcome.endpointHost.isPublicIpv4Address()) {
                    "couldn't reach your journal at ${outcome.endpointHost}:${outcome.endpointPort}. " +
                        "make sure it's running, then try again."
                } else {
                    "couldn't reach your journal at ${outcome.endpointHost}:${outcome.endpointPort}. " +
                        "make sure it's running and on the same wi-fi, then try again. some networks " +
                        "block devices from connecting directly. you can also switch your journal to " +
                        "private network to pair from anywhere."
                }
        }
    }

/** Classifies dotted IPv4 without DNS so rendering cannot trigger network I/O. */
private fun String.isPublicIpv4Address(): Boolean {
    val octets = split('.')
    if (octets.size != 4) return false
    val bytes = octets.map { part ->
        if (part.isEmpty() || (part.length > 1 && part.startsWith('0'))) return false
        part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
    }
    val first = bytes[0]
    val second = bytes[1]
    return when {
        first == 0 || first == 10 || first == 127 -> false
        first == 100 && second in 64..127 -> false
        first == 169 && second == 254 -> false
        first == 172 && second in 16..31 -> false
        first == 192 && second == 0 -> false
        first == 192 && second == 2 -> false
        first == 192 && second == 88 && bytes[2] == 99 -> false
        first == 192 && second == 168 -> false
        first == 198 && second in 18..19 -> false
        first == 198 && second == 51 && bytes[2] == 100 -> false
        first == 203 && second == 0 && bytes[2] == 113 -> false
        first >= 224 -> false
        else -> true
    }
}

fun PairAttemptOutcome.isSuccessfulPair(): Boolean =
    this is PairAttemptOutcome.Linked &&
        result.pairStatus in 200..299 &&
        result.statusStatus in 200..299
