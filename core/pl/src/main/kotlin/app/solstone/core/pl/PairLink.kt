// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.crypto.hex
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

sealed interface PairLinkAcceptance {
    data class Accepted(val canonicalHttps: String, val parsed: PairLink) : PairLinkAcceptance
    data object Rejected : PairLinkAcceptance
}

fun acceptPairLink(raw: String): PairLinkAcceptance {
    val trimmed = raw.trim()
    if (trimmed.contains('?') || trimmed.startsWith('#')) {
        return PairLinkAcceptance.Rejected
    }
    return try {
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase()
        when (scheme) {
            "https" -> {
                val parsed = parsePairLink(trimmed)
                PairLinkAcceptance.Accepted(canonicalHttps = trimmed, parsed = parsed)
            }
            "solstone" -> {
                val host = uri.host?.lowercase()
                val authority = uri.authority?.lowercase()
                val path = uri.path.orEmpty()
                val fragment = uri.rawFragment
                if (host == "pair" && authority == "pair" && (path.isEmpty() || path == "/") && !fragment.isNullOrEmpty()) {
                    val canonicalHttps = "https://go.solstone.app/p#$fragment"
                    val parsed = parsePairLink(canonicalHttps)
                    PairLinkAcceptance.Accepted(canonicalHttps = canonicalHttps, parsed = parsed)
                } else {
                    PairLinkAcceptance.Rejected
                }
            }
            else -> PairLinkAcceptance.Rejected
        }
    } catch (_: Exception) {
        PairLinkAcceptance.Rejected
    }
}

const val DEFAULT_DIRECT_PORT = 7657
val RECOGNIZED_PAIR_HOSTS = setOf("go.solstone.app")
const val PAIR_LINK_PATH = "/p"
class DirectEndpoint(host: String, port: Int) : app.solstone.core.model.DirectEndpoint(host, port)


sealed interface PairLink

class DirectPairLink(
    candidates: List<DirectEndpoint>,
    val nonce: String,
    val caFingerprintPrefix: ByteArray,
) : PairLink {
    val candidates: List<DirectEndpoint> = candidates.map { candidate ->
        DirectEndpoint(candidate.host, if (candidate.port <= 0) DEFAULT_DIRECT_PORT else candidate.port)
    }

    val host: String get() = candidates.first().host
    val port: Int get() = candidates.first().port

    fun endpoint(): DirectEndpoint = candidates.first()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DirectPairLink) return false
        return candidates == other.candidates &&
            nonce == other.nonce &&
            caFingerprintPrefix.contentEquals(other.caFingerprintPrefix)
    }

    override fun hashCode(): Int {
        var result = candidates.hashCode()
        result = 31 * result + nonce.hashCode()
        result = 31 * result + caFingerprintPrefix.contentHashCode()
        return result
    }

    override fun toString(): String =
        "DirectPairLink(candidates=$candidates, nonce=<redacted>, caFingerprintPrefix=<redacted>)"
}

class RelayPairLink(
    val s: ByteArray,
    val caFpSpki: ByteArray,
    val relayOrigin: String?,
) : PairLink {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RelayPairLink) return false
        return s.contentEquals(other.s) &&
            caFpSpki.contentEquals(other.caFpSpki) &&
            relayOrigin == other.relayOrigin
    }

    override fun hashCode(): Int {
        var result = s.contentHashCode()
        result = 31 * result + caFpSpki.contentHashCode()
        result = 31 * result + (relayOrigin?.hashCode() ?: 0)
        return result
    }

    // S is a pairing secret; redact it, the CA pin, and untrusted origin text from diagnostics.
    override fun toString(): String {
        val originKind = if (relayOrigin == null) "well-known" else "custom"
        return "RelayPairLink(s=<redacted>, caFpSpki=<redacted>, relayOrigin=<$originKind>)"
    }
}

enum class DialDecision { SUCCEED, ADVANCE, TERMINAL }

fun classifyPairResponseStatus(status: Int): DialDecision = when (status) {
    200 -> DialDecision.SUCCEED
    410 -> DialDecision.TERMINAL
    else -> DialDecision.ADVANCE
}

data class LocalIPv4Interface(val address: String, val prefixLength: Int)

fun looksLikePairLink(text: String?): Boolean {
    if (text == null) {
        return false
    }
    return try {
        val url = URL(text.trim())
        url.protocol == "https" &&
            url.host in RECOGNIZED_PAIR_HOSTS &&
            url.path == PAIR_LINK_PATH &&
            url.ref != null &&
            url.ref.isNotEmpty()
    } catch (_: Exception) {
        false
    }
}

fun parseDirectPairLink(pairLink: String): DirectPairLink {
    val decoded = decodePairLinkPayload(pairLink)
    return parseDirectFromDecoded(decoded)
}

fun parsePairLink(pairLink: String): PairLink {
    val decoded = decodePairLinkPayload(pairLink)
    return when (decoded.firstOrNull()) {
        0x06.toByte() -> parseRelayFromDecoded(decoded)
        else -> parseDirectFromDecoded(decoded)
    }
}

private fun decodePairLinkPayload(pairLink: String): ByteArray {
    val url = URL(pairLink.trim())
    if (url.protocol != "https" || url.host !in RECOGNIZED_PAIR_HOSTS || url.path != PAIR_LINK_PATH) {
        throw IllegalArgumentException("not a solstone pair link")
    }
    // An empty fragment decodes to an empty blob, which the form parser refuses as truncated.
    val fragment = url.ref ?: throw IllegalArgumentException("pair link missing fragment")
    return decodeCrockford32(fragment)
}

/**
 * Why a pair link was refused, with the details the SPL definition bundle's `error` objects carry.
 * Each refusal kind the corpus names has exactly one type here; the rest cover refusals the corpus
 * does not exercise.
 */
sealed interface PairLinkRefusal {
    /** The blob ends at [got] bytes, before the [expected] its form requires at that point. */
    data class Truncated(val expected: Int, val got: Int) : PairLinkRefusal

    /** An exact-length form carries [got] bytes instead of [expected]. */
    data class LengthMismatch(val expected: Int, val got: Int) : PairLinkRefusal

    /** A direct form uses address type [addressType] rather than IPv4. */
    data class UnsupportedAddressType(val addressType: Int) : PairLinkRefusal

    /** A direct candidate is not a dial target: the unspecified network or 224.0.0.0/3. */
    data class DisallowedDirectIpv4(val address: String) : PairLinkRefusal

    /** A multi-candidate form advertises [count] addresses, outside 1 through 4. */
    data class InvalidCandidateCount(val count: Int) : PairLinkRefusal

    /** A custom relay origin is not valid UTF-8. */
    data object BadRelayOrigin : PairLinkRefusal

    /** A relay form uses CA-fingerprint algorithm tag [tag]. */
    data class UnknownCaFpTag(val tag: Int) : PairLinkRefusal

    /** The decoded blob starts with form version [version], which this client does not implement. */
    data class UnsupportedVersion(val version: Int) : PairLinkRefusal
}

/** A pair link this client refuses; [refusal] carries the typed reason. */
class PairLinkRefusedException(val refusal: PairLinkRefusal) :
    IllegalArgumentException(refusalMessage(refusal))

private fun refusalMessage(refusal: PairLinkRefusal): String = when (refusal) {
    is PairLinkRefusal.Truncated,
    is PairLinkRefusal.LengthMismatch,
    is PairLinkRefusal.UnsupportedAddressType,
    is PairLinkRefusal.InvalidCandidateCount,
    is PairLinkRefusal.UnsupportedVersion,
    -> "unsupported pair link payload"
    is PairLinkRefusal.DisallowedDirectIpv4 -> "pair link candidate is outside the allowed IPv4 range"
    PairLinkRefusal.BadRelayOrigin -> "invalid relay origin"
    is PairLinkRefusal.UnknownCaFpTag -> "unsupported relay CA fingerprint tag"
}

private fun refuse(refusal: PairLinkRefusal): Nothing = throw PairLinkRefusedException(refusal)

private fun requireLength(decoded: ByteArray, end: Int) {
    if (decoded.size < end) refuse(PairLinkRefusal.Truncated(end, decoded.size))
}

private fun requireExactLength(decoded: ByteArray, expected: Int) {
    requireLength(decoded, expected)
    if (decoded.size != expected) refuse(PairLinkRefusal.LengthMismatch(expected, decoded.size))
}

private fun parseDirectFromDecoded(decoded: ByteArray): DirectPairLink {
    // The decoded blob bytes are authoritative; host/path only route to the pair payload.
    if (decoded.isEmpty()) refuse(PairLinkRefusal.Truncated(1, 0))
    return when (val version = decoded[0].toInt() and 0xff) {
        0x04 -> parseV04(decoded)
        0x05 -> parseV05(decoded)
        else -> refuse(PairLinkRefusal.UnsupportedVersion(version))
    }
}

private fun parseV04(decoded: ByteArray): DirectPairLink {
    requireLength(decoded, V04_LENGTH)
    val addressType = decoded[1].toInt() and 0xff
    if (addressType != ADDRESS_TYPE_IPV4) refuse(PairLinkRefusal.UnsupportedAddressType(addressType))
    requireExactLength(decoded, V04_LENGTH)
    val a = decoded[2].toInt() and 0xff
    val b = decoded[3].toInt() and 0xff
    val c = decoded[4].toInt() and 0xff
    val d = decoded[5].toInt() and 0xff
    if (!isDirectDialCandidate(a, b)) {
        refuse(PairLinkRefusal.DisallowedDirectIpv4("$a.$b.$c.$d"))
    }
    val host = "$a.$b.$c.$d"
    val port = ((decoded[6].toInt() and 0xff) shl 8) or (decoded[7].toInt() and 0xff)
    val normPort = if (port <= 0) DEFAULT_DIRECT_PORT else port
    val nonceBytes = decoded.copyOfRange(8, 24)
    val caFp = decoded.copyOfRange(24, 40)
    return DirectPairLink(listOf(DirectEndpoint(host, normPort)), hex(nonceBytes), caFp)
}

private fun parseV05(decoded: ByteArray): DirectPairLink {
    requireLength(decoded, 3)
    val addressType = decoded[1].toInt() and 0xff
    if (addressType != ADDRESS_TYPE_IPV4) refuse(PairLinkRefusal.UnsupportedAddressType(addressType))
    val count = decoded[2].toInt() and 0xff
    if (count < 1 || count > 4) refuse(PairLinkRefusal.InvalidCandidateCount(count))
    requireLength(decoded, 5)
    requireExactLength(decoded, 37 + 4 * count)
    val port = ((decoded[3].toInt() and 0xff) shl 8) or (decoded[4].toInt() and 0xff)
    val normPort = if (port <= 0) DEFAULT_DIRECT_PORT else port
    val candidates = mutableListOf<DirectEndpoint>()
    for (i in 0 until count) {
        val offset = 5 + 4 * i
        val a = decoded[offset].toInt() and 0xff
        val b = decoded[offset + 1].toInt() and 0xff
        val c = decoded[offset + 2].toInt() and 0xff
        val d = decoded[offset + 3].toInt() and 0xff
        if (!isDirectDialCandidate(a, b)) {
            refuse(PairLinkRefusal.DisallowedDirectIpv4("$a.$b.$c.$d"))
        }
        candidates.add(DirectEndpoint("$a.$b.$c.$d", normPort))
    }
    val nonceOffset = 5 + 4 * count
    val nonceBytes = decoded.copyOfRange(nonceOffset, nonceOffset + 16)
    val caFp = decoded.copyOfRange(nonceOffset + 16, nonceOffset + 32)
    return DirectPairLink(candidates.distinct(), hex(nonceBytes), caFp)
}

private fun parseRelayFromDecoded(decoded: ByteArray): RelayPairLink {
    requireLength(decoded, RELAY_BASE_LENGTH)
    val version = decoded[0].toInt() and 0xff
    if (version != 0x06) refuse(PairLinkRefusal.UnsupportedVersion(version))
    val s = decoded.copyOfRange(1, 9)
    val caFpTag = decoded[9].toInt() and 0xff
    if (caFpTag != 0x01) refuse(PairLinkRefusal.UnknownCaFpTag(caFpTag))
    val caFpSpki = decoded.copyOfRange(10, 26)
    val selector = decoded[26].toInt() and 0xff
    val relayOrigin = if (selector == 0) {
        requireExactLength(decoded, RELAY_BASE_LENGTH)
        null
    } else {
        requireExactLength(decoded, RELAY_BASE_LENGTH + selector)
        val originBytes = decoded.copyOfRange(RELAY_BASE_LENGTH, RELAY_BASE_LENGTH + selector)
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(originBytes))
                .toString()
        } catch (_: CharacterCodingException) {
            refuse(PairLinkRefusal.BadRelayOrigin)
        }
    }
    return RelayPairLink(s, caFpSpki, relayOrigin)
}

private const val ADDRESS_TYPE_IPV4 = 0x01
private const val V04_LENGTH = 40
private const val RELAY_BASE_LENGTH = 27

// No private/LAN-only restriction: a direct pair link's trust anchor is the
// embedded CA-fingerprint pin, checked at TLS handshake time, not the network
// locality of the address it dials (restriction removed 2026-09-18). The only
// addresses that are never valid dial targets are the unspecified network
// (0.0.0.0/8) and multicast/reserved (224.0.0.0/3).
private fun isDirectDialCandidate(a: Int, b: Int): Boolean = a != 0 && a < 224

fun supportedDirectDialEndpoint(ip: String, port: Int): DirectEndpoint? {
    val parts = ip.split(".")
    if (parts.size != 4) return null
    val a = parts[0].toIntOrNull() ?: return null
    val b = parts[1].toIntOrNull() ?: return null
    val c = parts[2].toIntOrNull() ?: return null
    val d = parts[3].toIntOrNull() ?: return null
    if (a !in 0..255 || b !in 0..255 || c !in 0..255 || d !in 0..255) return null
    if (!isDirectDialCandidate(a, b)) return null
    val normPort = if (port <= 0) DEFAULT_DIRECT_PORT else if (port > 65535) return null else port
    return DirectEndpoint("$a.$b.$c.$d", normPort)
}

fun orderCandidatesBySubnet(
    candidates: List<DirectEndpoint>,
    interfaces: List<LocalIPv4Interface>,
): List<DirectEndpoint> {
    val usable = interfaces.mapNotNull { iface ->
        val packed = packDottedIPv4(iface.address)
        if (packed != null && iface.prefixLength in 1..32) {
            iface to packed
        } else {
            null
        }
    }
    if (usable.isEmpty()) {
        return candidates
    }

    val (onSubnet, offSubnet) = candidates.partition { candidate ->
        val target = packDottedIPv4(candidate.host) ?: return@partition false
        usable.any { (iface, ifacePacked) ->
            val mask = -1 shl (32 - iface.prefixLength)
            (target and mask) == (ifacePacked and mask)
        }
    }
    return onSubnet + offSubnet
}

private fun packDottedIPv4(address: String): Int? {
    val parts = address.split(".")
    if (parts.size != 4) {
        return null
    }
    var packed = 0
    for (part in parts) {
        val octet = part.toIntOrNull() ?: return null
        if (octet !in 0..255) {
            return null
        }
        packed = (packed shl 8) or octet
    }
    return packed
}

fun decodeCrockford32(text: String): ByteArray {
    val out = ByteArrayOutputStream()
    var buffer = 0
    var bits = 0
    for (raw in text) {
        val value = crockfordValue(raw)
        if (value < 0) {
            continue
        }
        buffer = (buffer shl 5) or value
        bits += 5
        while (bits >= 8) {
            bits -= 8
            out.write((buffer shr bits) and 0xff)
            buffer = buffer and ((1 shl bits) - 1)
        }
    }
    if (bits > 0 && (buffer and ((1 shl bits) - 1)) != 0) {
        throw IllegalArgumentException("non-zero trailing pair-link pad bits")
    }
    return out.toByteArray()
}

fun crockfordValue(raw: Char): Int {
    if (raw == '-' || raw.isWhitespace()) {
        return -1
    }
    var c = raw.uppercaseChar()
    if (c == 'I' || c == 'L') {
        c = '1'
    } else if (c == 'O') {
        c = '0'
    }
    val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    val value = alphabet.indexOf(c)
    if (value < 0) {
        throw IllegalArgumentException("bad Crockford base32 char: $raw")
    }
    return value
}
