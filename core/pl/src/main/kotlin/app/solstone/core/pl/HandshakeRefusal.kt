// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.io.IOException
import javax.net.ssl.SSLException

// The client rules for a journal that refuses the inner TLS handshake (`proto/session.md` § 7).
//
// Access denied (49) means this device is not paired: stop. Certificate unknown (46) means the
// journal could not read its pairing records: retry and keep the credential, unbounded. Any other
// refusal retries until refusals have gone on long enough, then stops as unpaired. A journal that
// was never reached, or a peer that is not the paired journal, is not refusing anything and never
// counts.

/** Counted refusals that stop a credential with [HandshakeStop.REFUSALS_EXHAUSTED]. */
const val REFUSAL_LIMIT: Int = 7

/**
 * Minimum spacing between two counted refusals. Refusals closer together count once, so a burst
 * of retries cannot reach the limit; time offline never advances the count.
 */
const val REFUSAL_SPACING_MILLIS: Long = 300_000L

/** How one attempt to reach the paired journal ended, when it did not end in an answer. */
enum class HandshakeFailure {
    /**
     * The journal was not reached: no route, a timeout, a reset, an EOF, a clean close, a TLS or
     * record error this client detected itself, an alert before this client confirmed the
     * journal, a failure of the relay's own connection, or the relay's rejection of the device.
     * Retry; never counts.
     */
    UNREACHABLE,

    /** The journal refused this device with access denied (49). Stop. */
    TLS_ACCESS_DENIED,

    /** The journal could not read its pairing records (46). Retry; neither counts nor clears. */
    TLS_CERTIFICATE_UNKNOWN,

    /** The journal refused this device with any other received fatal alert. Counted. */
    TLS_REFUSED,

    /** A peer that is not the paired journal answered. Retry; never counts, never clears. */
    UNKNOWN_JOURNAL,
}

/** Why a credential stopped. Both present the unpaired state; only a new pairing ends a stop. */
enum class HandshakeStop {
    TLS_ACCESS_DENIED,
    REFUSALS_EXHAUSTED,
}

/**
 * The journal's verdict on this device: a fatal alert received after this client confirmed the
 * journal against the pinned CA and sent its own Finished, and before the journal's first
 * application byte. [failure] is [HandshakeFailure.TLS_ACCESS_DENIED],
 * [HandshakeFailure.TLS_CERTIFICATE_UNKNOWN] or [HandshakeFailure.TLS_REFUSED]. [alertCode] is
 * null when the platform reported a received alert without a usable code.
 */
class JournalRefusalException(
    val failure: HandshakeFailure,
    val alertCode: Int?,
    cause: Throwable? = null,
) : IOException("journal refused this device" + (alertCode?.let { " (alert $it)" } ?: ""), cause) {
    init {
        require(
            failure == HandshakeFailure.TLS_ACCESS_DENIED ||
                failure == HandshakeFailure.TLS_CERTIFICATE_UNKNOWN ||
                failure == HandshakeFailure.TLS_REFUSED,
        ) { "a refusal is 49, 46 or another received alert" }
    }
}

/**
 * A peer that answered where the paired journal was expected, but whose certificate does not chain
 * to the pinned CA. [address] is the direct `host:port` it answered at, or null through the relay.
 * [claimedJournalId] is the journal ID of the self-signed P-256 CA the peer presented: only what it
 * claims, since any peer can present another journal's public certificate. Show it; never decide on
 * it. Null when the peer presented no such CA, or presented the paired journal's own CA without a
 * certificate that CA signed.
 */
class UnknownJournalException(
    val address: String?,
    val claimedJournalId: String?,
    cause: Throwable? = null,
) : IOException("peer is not the paired journal", cause)

/** Told what the journal said inside one connection's verdict window. */
interface JournalVerdictListener {
    /** The journal sent its first application byte: it accepted this device. */
    fun onAccepted()

    /** The journal refused this device; [refusal] carries the classified alert. */
    fun onRefused(refusal: JournalRefusalException)
}

/**
 * Classify a failed attempt. Only the typed results of the transport's own checks are refusals or
 * unknown journals; everything else, every other TLS exception included, is unreachable.
 */
fun handshakeFailureOf(failure: Throwable): HandshakeFailure {
    causeChain(failure).forEach { cause ->
        when (cause) {
            is JournalRefusalException -> return cause.failure
            is UnknownJournalException -> return HandshakeFailure.UNKNOWN_JOURNAL
        }
    }
    return HandshakeFailure.UNREACHABLE
}

/** The [JournalRefusalException] anywhere in [failure]'s cause chain, if any. */
fun journalRefusalIn(failure: Throwable): JournalRefusalException? =
    causeChain(failure).firstNotNullOfOrNull { it as? JournalRefusalException }

/** The [UnknownJournalException] anywhere in [failure]'s cause chain, if any. */
fun unknownJournalIn(failure: Throwable): UnknownJournalException? =
    causeChain(failure).firstNotNullOfOrNull { it as? UnknownJournalException }

/**
 * A fatal alert the peer sent, read from a platform TLS exception. [code] is null when the platform
 * named a received alert without a code this client can read.
 */
data class ReceivedTlsAlert(val code: Int?)

/**
 * The received fatal alert [failure] reports, or null when it reports anything else.
 *
 * Reads the two stacks this client runs on. BoringSSL (Conscrypt, on a device) reports a received
 * alert as library error reason `1000 + code`, packed into the `error:XXXXXXXX:SSL routines:` code,
 * and names known ones `TLSV1_ALERT_*` / `SSLV3_ALERT_*`. JSSE (the host JVM) says
 * `Received fatal alert: <name>`, or `Unknown alert description (<code>)` for an unassigned code.
 * Only [SSLException]s are read, so a relay's close reason can never pose as an alert.
 */
fun receivedTlsAlert(failure: Throwable): ReceivedTlsAlert? {
    causeChain(failure).filterIsInstance<SSLException>().forEach { ssl ->
        val message = ssl.message ?: return@forEach
        BORINGSSL_PACKED.findAll(message).forEach { match ->
            val packed = match.groupValues[1].toLong(16)
            val library = (packed ushr 24) and 0xff
            val reason = (packed and 0xfff).toInt()
            if (library == BORINGSSL_LIB_SSL && reason in ALERT_REASON_OFFSET..ALERT_REASON_OFFSET + 255) {
                return ReceivedTlsAlert(reason - ALERT_REASON_OFFSET)
            }
        }
        BORINGSSL_ALERT_NUMBER.find(message)?.let { return ReceivedTlsAlert(it.groupValues[1].toInt()) }
        BORINGSSL_ALERT_NAME.find(message)?.let { match ->
            return ReceivedTlsAlert(BORINGSSL_ALERT_CODES[match.groupValues[1]])
        }
        // JSSE prints the alert byte signed: 200 reads as -56.
        JSSE_UNKNOWN_ALERT.find(message)?.let { return ReceivedTlsAlert(it.groupValues[1].toInt() and 0xff) }
        JSSE_RECEIVED_ALERT.find(message)?.let { match ->
            return ReceivedTlsAlert(JSSE_ALERT_CODES[match.groupValues[1]])
        }
    }
    return null
}

/**
 * The refusal a received alert inside the verdict window means. Any received fatal alert is a
 * refusal: 49 and 46 have their own meaning and every other code, unassigned or unnamed ones
 * included, is counted. ⛔ Do not narrow this to known codes.
 */
fun refusalForAlert(alert: ReceivedTlsAlert): HandshakeFailure = when (alert.code) {
    ALERT_ACCESS_DENIED -> HandshakeFailure.TLS_ACCESS_DENIED
    ALERT_CERTIFICATE_UNKNOWN -> HandshakeFailure.TLS_CERTIFICATE_UNKNOWN
    else -> HandshakeFailure.TLS_REFUSED
}

const val ALERT_ACCESS_DENIED: Int = 49
const val ALERT_CERTIFICATE_UNKNOWN: Int = 46

internal fun causeChain(failure: Throwable): Sequence<Throwable> {
    val seen = HashSet<Throwable>()
    return generateSequence(failure) { it.cause }.takeWhile { seen.add(it) }.take(16)
}

private const val BORINGSSL_LIB_SSL = 0x10L
private const val ALERT_REASON_OFFSET = 1000
private val BORINGSSL_PACKED = Regex("""error:([0-9A-Fa-f]{8}):SSL routines""")
private val BORINGSSL_ALERT_NUMBER = Regex("""SSL alert number (\d{1,3})""")
private val BORINGSSL_ALERT_NAME = Regex("""\b((?:SSLV3|TLSV1|TLSV1_3|TLSV13)_ALERT_[A-Z0-9_]+)""")
private val JSSE_RECEIVED_ALERT = Regex("""Received fatal alert: ([a-z0-9_]+)""")
private val JSSE_UNKNOWN_ALERT = Regex("""Unknown alert description \((-?\d{1,3})\)""")

private val JSSE_ALERT_CODES = mapOf(
    "close_notify" to 0, "unexpected_message" to 10, "bad_record_mac" to 20, "decryption_failed" to 21,
    "record_overflow" to 22, "decompression_failure" to 30, "handshake_failure" to 40, "no_certificate" to 41,
    "bad_certificate" to 42, "unsupported_certificate" to 43, "certificate_revoked" to 44,
    "certificate_expired" to 45, "certificate_unknown" to 46, "illegal_parameter" to 47, "unknown_ca" to 48,
    "access_denied" to 49, "decode_error" to 50, "decrypt_error" to 51, "export_restriction" to 60,
    "protocol_version" to 70, "insufficient_security" to 71, "internal_error" to 80,
    "inappropriate_fallback" to 86, "user_canceled" to 90, "no_renegotiation" to 100,
    "missing_extension" to 109, "unsupported_extension" to 110, "certificate_unobtainable" to 111,
    "unrecognized_name" to 112, "bad_certificate_status_response" to 113, "bad_certificate_hash_value" to 114,
    "unknown_psk_identity" to 115, "certificate_required" to 116, "no_application_protocol" to 120,
)

private val BORINGSSL_ALERT_CODES = mapOf(
    "SSLV3_ALERT_CLOSE_NOTIFY" to 0, "SSLV3_ALERT_UNEXPECTED_MESSAGE" to 10, "SSLV3_ALERT_BAD_RECORD_MAC" to 20,
    "TLSV1_ALERT_DECRYPTION_FAILED" to 21, "TLSV1_ALERT_RECORD_OVERFLOW" to 22,
    "SSLV3_ALERT_DECOMPRESSION_FAILURE" to 30, "SSLV3_ALERT_HANDSHAKE_FAILURE" to 40,
    "SSLV3_ALERT_NO_CERTIFICATE" to 41, "SSLV3_ALERT_BAD_CERTIFICATE" to 42,
    "SSLV3_ALERT_UNSUPPORTED_CERTIFICATE" to 43, "SSLV3_ALERT_CERTIFICATE_REVOKED" to 44,
    "SSLV3_ALERT_CERTIFICATE_EXPIRED" to 45, "SSLV3_ALERT_CERTIFICATE_UNKNOWN" to 46,
    "SSLV3_ALERT_ILLEGAL_PARAMETER" to 47, "TLSV1_ALERT_UNKNOWN_CA" to 48, "TLSV1_ALERT_ACCESS_DENIED" to 49,
    "TLSV1_ALERT_DECODE_ERROR" to 50, "TLSV1_ALERT_DECRYPT_ERROR" to 51, "TLSV1_ALERT_EXPORT_RESTRICTION" to 60,
    "TLSV1_ALERT_PROTOCOL_VERSION" to 70, "TLSV1_ALERT_INSUFFICIENT_SECURITY" to 71,
    "TLSV1_ALERT_INTERNAL_ERROR" to 80, "TLSV1_ALERT_INAPPROPRIATE_FALLBACK" to 86,
    "TLSV1_ALERT_USER_CANCELLED" to 90, "TLSV1_ALERT_NO_RENEGOTIATION" to 100,
    "TLSV1_ALERT_UNSUPPORTED_EXTENSION" to 110, "TLSV1_ALERT_CERTIFICATE_UNOBTAINABLE" to 111,
    "TLSV1_ALERT_UNRECOGNIZED_NAME" to 112, "TLSV1_ALERT_BAD_CERTIFICATE_STATUS_RESPONSE" to 113,
    "TLSV1_ALERT_BAD_CERTIFICATE_HASH_VALUE" to 114, "TLSV1_ALERT_UNKNOWN_PSK_IDENTITY" to 115,
    "TLSV1_ALERT_CERTIFICATE_REQUIRED" to 116, "TLSV1_ALERT_NO_APPLICATION_PROTOCOL" to 120,
    "TLSV1_ALERT_ECH_REQUIRED" to 121,
)
