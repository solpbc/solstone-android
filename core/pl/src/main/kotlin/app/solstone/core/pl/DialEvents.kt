// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.crypto.CaPinException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * What happened when the app dialed one address.
 *
 * [key] is the diagnostic word written to the event log; it is not owner copy.
 */
enum class DialOutcome(val key: String) {
    /** Nothing answered: a timeout, no route, or a name that did not resolve. */
    NO_ANSWER("no-answer"),

    /** Something answered, but it could not prove it is the owner's journal (TLS or pin). */
    NOT_VERIFIED("not-verified"),

    /** Anything else that stopped the connection. Never a timeout. */
    FAILED("failed"),

    /** The address answered and proved itself. Logged only when it is a change. */
    CONNECTED("connected"),
}

/** Maps a dial failure to its outcome, looking through wrapping exceptions. */
fun classifyDialFailure(failure: Throwable): DialOutcome {
    val chain = generateSequence(failure) { it.cause }.take(16).toList()
    return when {
        chain.any {
            it is SocketTimeoutException || it is NoRouteToHostException || it is UnknownHostException
        } -> DialOutcome.NO_ANSWER
        chain.any { it is CaPinException || it is SSLException } -> DialOutcome.NOT_VERIFIED
        else -> DialOutcome.FAILED
    }
}

/**
 * `host:port` as an owner reads it, with an IPv6 literal bracketed (a `%zone` stays inside).
 */
fun displayAddress(host: String, port: Int): String {
    val bracketed = if (host.count { it == ':' } >= 2 && !host.startsWith("[")) "[$host]" else host
    return "$bracketed:$port"
}

/**
 * One dial event line.
 *
 * ⛔ Built from the explicit host, port and outcome only. Never pass a transport, pairing or link
 * object's text here: a relay transport's `toString` carries its device token and instance ID,
 * and a pair link carries its nonce.
 */
fun formatDialEvent(host: String, port: Int, outcome: DialOutcome): String =
    "kind=dial host=${host.filterNot(Char::isWhitespace)} port=$port outcome=${outcome.key}"

/**
 * Writes dial events to the diagnostic log: every failure, and a success only when it is a change
 * (the first one, one after a failure, or one at a different address). A healthy sync that dials
 * the same address every few minutes therefore writes nothing, so the capped log keeps room for
 * other events.
 */
class DialEventLog(private val emit: (String) -> Unit) {
    private val lock = Any()
    private var lastConnected: Pair<String, Int>? = null

    fun record(host: String, port: Int, outcome: DialOutcome) {
        val line = synchronized(lock) {
            if (outcome == DialOutcome.CONNECTED) {
                val address = host to port
                if (lastConnected == address) return
                lastConnected = address
            } else {
                lastConnected = null
            }
            formatDialEvent(host, port, outcome)
        }
        runCatching { emit(line) }
    }

    fun recordFailure(host: String, port: Int, failure: Throwable) {
        record(host, port, classifyDialFailure(failure))
    }
}
