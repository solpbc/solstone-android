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
 * The scanner's camera did not start, whatever the platform said about why.
 *
 * ⛔ **The platform's reason goes to the log, never to the owner.** It used to be appended here, and
 * what the owner read was a permission-check function name, a process id and a uid.
 */
const val CAMERA_COULD_NOT_START =
    "the camera couldn't start. try again, and if another app is using the camera, close it first."

/** This app's own camera lock refused the scanner: a capture of ours is holding the camera. */
// ⚠ The still engine acquires this lock per capture and releases it in a `finally`, so the
// condition clears on its own within one still. ⛔ Do not steer the owner into turning off a
// source they want on as if it were the only way through.
const val CAMERA_IN_USE_BY_THIS_APP =
    "the solstone app is using the camera right now. try again, or turn the camera source off " +
        "to scan."

/** The owner declined the camera, so the scanner cannot open; the link route still can. */
const val CAMERA_OFF_FOR_SCAN =
    "the camera is off for the solstone app, so it can't scan a pairing code. turn it on in " +
        "android settings, or open your journal's pairing link on this phone instead."

const val OPEN_ANDROID_SETTINGS = "open android settings"

/**
 * The scanner's own running state, under the instruction to point the phone at the code.
 *
 * ⚠ Was `Scanning`, capitalised, which is the only capitalised word on that screen and reads as
 * a machine reporting a mode rather than the app saying what it is doing.
 */
const val SCANNER_LOOKING = "looking for a code"

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
        PairAttemptOutcome.ExistingPairingActive ->
            "this phone is already connected to a working journal. unpair this phone, then try again to connect a different journal."
        PairAttemptOutcome.ExistingPairingUnreachable ->
            "couldn't check the journal this phone is already connected to, so nothing changed. make sure your journal is reachable, then try again."
        // ⛔ Not a scanner-state word, and ⛔ not `already pairing` either. `withPairLock` returns
        // null on TWO conditions — the camera lock and the sync drain gate — and on the scanner
        // path the camera leg is already held by the scan session, so a draining sync is the only
        // producer there. Naming either cause is wrong about half the time; the honest word is
        // the one that covers both.
        PairAttemptOutcome.Retry -> "the app is busy. give it a moment."
        is PairAttemptOutcome.NetworkUnavailable -> networkFailureText(outcome)
        // ⚠ 410 and every other status collapsed into one message. Both mean the code is no longer
        // good and both need the same next step, so splitting them told the owner there was a
        // difference and then declined to say what it was.
        is PairAttemptOutcome.WindowClosed -> PAIR_CODE_EXPIRED
        is PairAttemptOutcome.OtherFailure -> PAIR_GENERIC
        // The first candidate that answered without proving itself is the one named.
        is PairAttemptOutcome.NotVerified ->
            "something answered at ${authority(outcome.endpointHost, outcome.endpointPort)} " +
                "but couldn't prove it's your journal. make sure you're connected to your " +
                "journal's network, directly or over your vpn, then show a new pairing code on " +
                "your journal and try again."
    }

private fun networkFailureText(outcome: PairAttemptOutcome.NetworkUnavailable): String =
    when (outcome.failure) {
        ConnectivityFailure.DEVICE_OFFLINE ->
            "this device isn't on a network. get back on a network and try again. what the " +
                "solstone app has taken in is on this device, and goes to your journal as soon " +
                "as it can reach it."
        ConnectivityFailure.NAME_RESOLUTION ->
            "couldn't find your journal at ${outcome.endpointHost}. check the address, then try " +
                "again with a new pairing code."
        ConnectivityFailure.HOST_DID_NOT_ANSWER -> when (outcome.route) {
            PairRoute.RELAY ->
                "couldn't reach your journal at ${authority(outcome)}. " +
                    "make sure it's running, then try again."
            PairRoute.DIRECT ->
                if (outcome.endpointHost.isPublicAddress()) {
                    "couldn't reach your journal at ${authority(outcome)}. " +
                        "make sure it's running, then try again."
                } else {
                    "couldn't reach your journal at ${authority(outcome)}. " +
                        "make sure it's running and on the same wi-fi, then try again. some networks " +
                        "block devices from connecting directly. you can also switch your journal to " +
                        "private network to pair from anywhere."
                }
        }
    }

/**
 * `host:port`, with an IPv6 literal bracketed so the port is readable.
 *
 * 🔴 A bare IPv6 literal rendered `at 2001:db8::1:7657.` — the port ran straight on from the
 * address with nothing to separate them, so the owner could not tell where the address ended.
 * RFC 3986 § 3.2.2 gives the bracketed form for exactly this. ⛔ Not a new sentence: the
 * template is unchanged and already gated, only the authority inside it is now unambiguous.
 */
private fun authority(outcome: PairAttemptOutcome.NetworkUnavailable): String =
    authority(outcome.endpointHost, outcome.endpointPort)

private fun authority(host: String, port: Int): String {
    val bracketed = if (host.count { it == ':' } >= 2 && !host.startsWith("[")) "[$host]" else host
    return "$bracketed:$port"
}

/**
 * Is this endpoint reachable from anywhere, rather than only from the journal's own network?
 *
 * ⚠ The wi-fi advice below is only true for a target that has to be on the same network. It was
 * gated on dotted IPv4 alone, so a journal on a **public IPv6** address was told to check its
 * wi-fi and to "switch to private network to pair from anywhere" — advice that is false when the
 * address already reaches from anywhere.
 *
 * ⛔ A hostname is deliberately NOT public here: it may resolve anywhere, and VPX ruled on
 * 2026-09-19 (`req_vb6rta4j`) that hostnames keep the wi-fi message. This only adds the literal
 * case, where the address itself settles the question with no lookup.
 *
 * ⚠ Documentation-range IPv4 (`192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24`) is public on
 * purpose so a tester has a safe address that takes the public-branch copy. The v4 non-public set
 * is exactly six families: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `100.64.0.0/10`,
 * `127.0.0.0/8`, and `169.254.0.0/16`. Two retained divergences: `first == 0` and `first >= 224`
 * stay non-public here; they cannot reach this renderer from a pair link because
 * `PairLink.isDirectDialCandidate` is `a != 0 && a < 224` and the pair-link address type is `0x01` only.
 */
private fun String.isPublicAddress(): Boolean =
    if (count { it == ':' } >= 2) isPublicIpv6Address() else isPublicIpv4Address()

/**
 * Classifies an IPv6 literal without DNS; documentation-range `2001:db8::/32` is public, matching v4.
 *
 * ⚠ `2001:db8::/32` is public, matching the v4 documentation ranges. The v6 branch is
 * defense-in-depth: no pair link carries an IPv6 literal (`parseDirectFromDecoded` requires type
 * `0x01`).
 */
private fun String.isPublicIpv6Address(): Boolean {
    val bare = removePrefix("[").removeSuffix("]").substringBefore('%')
    // ⛔ An IPv4-mapped or IPv4-compatible form is an IPv4 question; defer rather than guess.
    val tail = bare.substringAfterLast(':')
    if (tail.contains('.')) return tail.isPublicIpv4Address()
    val groups = expandIpv6(bare) ?: return false
    val first = groups[0]
    return when {
        groups.all { it == 0 } -> false                       // ::            unspecified
        groups.take(7).all { it == 0 } && groups[7] == 1 -> false  // ::1       loopback
        first and 0xFF00 == 0xFF00 -> false                   // ff00::/8      multicast
        first and 0xFFC0 == 0xFE80 -> false                   // fe80::/10     link-local
        first and 0xFE00 == 0xFC00 -> false                   // fc00::/7      unique-local
        first == 0x0100 && groups[1] == 0 -> false             // 100::/64      discard-only
        else -> true
    }
}

/** Expands an IPv6 literal to its eight groups, or null if it is not one. */
private fun expandIpv6(text: String): List<Int>? {
    if (text.isEmpty() || text.count { it == ':' } > 8) return null
    val halves = text.split("::")
    if (halves.size > 2) return null
    fun parse(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        return part.split(':').map { g ->
            if (g.isEmpty() || g.length > 4) return null
            g.toIntOrNull(16) ?: return null
        }
    }
    val head = parse(halves[0]) ?: return null
    return if (halves.size == 1) {
        head.takeIf { it.size == 8 }
    } else {
        val tail = parse(halves[1]) ?: return null
        if (head.size + tail.size > 7) return null
        head + List(8 - head.size - tail.size) { 0 } + tail
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
        first == 192 && second == 168 -> false
        first >= 224 -> false
        else -> true
    }
}

fun PairAttemptOutcome.isSuccessfulPair(): Boolean =
    this is PairAttemptOutcome.Linked &&
        result.pairStatus in 200..299 &&
        result.statusStatus in 200..299

/**
 * Whether tapping the SAME link again is an honestly plausible fix, per the
 * needs-attention-recovery rule that a retry is only offered where it can plausibly work.
 *
 * ⛔ Only ever called on a non-successful result — [showPairLink]'s success branch routes to
 * [ObserverHarnessUi.showPaired] before this is consulted, so [PairAttemptOutcome.Linked] here
 * is always the non-2xx case ([pairStatusText]'s `PAIR_GENERIC` branch).
 */
fun PairLinkDispatchResult.isRetryableSameLink(): Boolean = when (this) {
    PairLinkDispatchResult.NoLink -> false
    // Malformed once, malformed again — the same bytes decode the same way every time.
    PairLinkDispatchResult.InvalidLink -> false
    // Transient: the prior attempt was still in flight, not that this link cannot work.
    PairLinkDispatchResult.Busy -> true
    is PairLinkDispatchResult.Attempted -> when (this.outcome) {
        // Non-2xx `Linked` renders PAIR_GENERIC, whose own words send the owner back to the
        // journal for a fresh code — this link cannot become a different code by retrying.
        is PairAttemptOutcome.Linked -> false
        // The fix is to unpair first, on a different screen; retrying this link does that to
        // nobody.
        PairAttemptOutcome.ExistingPairingActive -> false
        // The existing pairing's reachability is what failed to check, not this link.
        PairAttemptOutcome.ExistingPairingUnreachable -> true
        PairAttemptOutcome.Retry -> true
        is PairAttemptOutcome.NetworkUnavailable -> true
        // The answer came from whatever the phone's current network routed to; after a network
        // change (a VPN off, back on the home wi-fi) the same code can reach the journal.
        is PairAttemptOutcome.NotVerified -> true
        // The window is closed; the same link is the same expired code.
        is PairAttemptOutcome.WindowClosed -> false
        // PAIR_GENERIC's words point at a new code, same reasoning as the Linked branch above.
        is PairAttemptOutcome.OtherFailure -> false
    }
}
