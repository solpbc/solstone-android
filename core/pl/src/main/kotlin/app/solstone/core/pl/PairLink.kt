// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.crypto.hex
import java.io.ByteArrayOutputStream
import java.net.URL

const val DEFAULT_DIRECT_PORT = 7657
val RECOGNIZED_PAIR_HOSTS = setOf("go.solstone.app")
const val PAIR_LINK_PATH = "/p"

data class DirectEndpoint(val host: String, val port: Int)

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
        "DirectPairLink(candidates=$candidates, nonce=$nonce, caFingerprintPrefix=${caFingerprintPrefix.contentToString()})"
}

class RelayPairLink(
    val instanceId: String,
    val totp: String,
    val nonce: String,
    val caFpTag: Int,
    val caFpPrefix: ByteArray,
    val relayOrigin: String?,
) : PairLink {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RelayPairLink) return false
        return instanceId == other.instanceId &&
            totp == other.totp &&
            nonce == other.nonce &&
            caFpTag == other.caFpTag &&
            caFpPrefix.contentEquals(other.caFpPrefix) &&
            relayOrigin == other.relayOrigin
    }

    override fun hashCode(): Int {
        var result = instanceId.hashCode()
        result = 31 * result + totp.hashCode()
        result = 31 * result + nonce.hashCode()
        result = 31 * result + caFpTag
        result = 31 * result + caFpPrefix.contentHashCode()
        result = 31 * result + (relayOrigin?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "RelayPairLink(instanceId=$instanceId, totp=$totp, nonce=$nonce, caFpTag=$caFpTag, caFpPrefix=${caFpPrefix.contentToString()}, relayOrigin=$relayOrigin)"
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
        0x03.toByte() -> parseRelayFromDecoded(decoded)
        else -> parseDirectFromDecoded(decoded)
    }
}

private fun decodePairLinkPayload(pairLink: String): ByteArray {
    val url = URL(pairLink.trim())
    if (url.protocol != "https" || url.host !in RECOGNIZED_PAIR_HOSTS || url.path != PAIR_LINK_PATH) {
        throw IllegalArgumentException("not a solstone pair link")
    }
    val fragment = url.ref
    if (fragment == null || fragment.isEmpty()) {
        throw IllegalArgumentException("pair link missing fragment")
    }
    return decodeCrockford32(fragment)
}

private fun parseDirectFromDecoded(decoded: ByteArray): DirectPairLink {
    // The decoded blob bytes are authoritative; host/path only route to the pair payload.
    if (decoded.size == 40 && decoded[0] == 0x04.toByte() && decoded[1] == 0x01.toByte()) {
        val a = decoded[2].toInt() and 0xff
        val b = decoded[3].toInt() and 0xff
        val c = decoded[4].toInt() and 0xff
        val d = decoded[5].toInt() and 0xff
        if (!isPrivateOrLinkLocal(a, b)) {
            throw IllegalArgumentException("pair link is not local/private IPv4")
        }
        val host = "$a.$b.$c.$d"
        val port = ((decoded[6].toInt() and 0xff) shl 8) or (decoded[7].toInt() and 0xff)
        val normPort = if (port <= 0) DEFAULT_DIRECT_PORT else port
        val nonceBytes = decoded.copyOfRange(8, 24)
        val caFp = decoded.copyOfRange(24, 40)
        return DirectPairLink(listOf(DirectEndpoint(host, normPort)), hex(nonceBytes), caFp)
    }

    if (decoded.size >= 3 && decoded[0] == 0x05.toByte() && decoded[1] == 0x01.toByte()) {
        val count = decoded[2].toInt() and 0xff
        if (count < 1 || decoded.size != 37 + 4 * count) {
            throw IllegalArgumentException("unsupported pair link payload")
        }
        val port = ((decoded[3].toInt() and 0xff) shl 8) or (decoded[4].toInt() and 0xff)
        val normPort = if (port <= 0) DEFAULT_DIRECT_PORT else port
        val candidates = mutableListOf<DirectEndpoint>()
        for (i in 0 until count) {
            val offset = 5 + 4 * i
            val a = decoded[offset].toInt() and 0xff
            val b = decoded[offset + 1].toInt() and 0xff
            val c = decoded[offset + 2].toInt() and 0xff
            val d = decoded[offset + 3].toInt() and 0xff
            if (a != 127) {
                candidates.add(DirectEndpoint("$a.$b.$c.$d", normPort))
            }
        }
        if (candidates.isEmpty()) {
            throw IllegalArgumentException("pair link has no usable direct candidates")
        }
        val nonceOffset = 5 + 4 * count
        val nonceBytes = decoded.copyOfRange(nonceOffset, nonceOffset + 16)
        val caFp = decoded.copyOfRange(nonceOffset + 16, nonceOffset + 32)
        return DirectPairLink(candidates, hex(nonceBytes), caFp)
    }

    throw IllegalArgumentException("unsupported pair link payload")
}

private fun parseRelayFromDecoded(decoded: ByteArray): RelayPairLink {
    if (decoded.size < 54) {
        throw IllegalArgumentException("unsupported relay pair link payload")
    }
    val selector = decoded[53].toInt() and 0xff
    val expectedLength = if (selector == 0) 54 else 54 + selector
    if (decoded.size != expectedLength) {
        throw IllegalArgumentException("unsupported relay pair link payload")
    }
    val instanceBytes = decoded.copyOfRange(1, 17)
    val totp = (
        ((decoded[17].toInt() and 0xff) shl 16) or
            ((decoded[18].toInt() and 0xff) shl 8) or
            (decoded[19].toInt() and 0xff)
        ).toString().padStart(6, '0')
    val nonce = hex(decoded.copyOfRange(20, 36))
    val caFpTag = decoded[36].toInt() and 0xff
    val caFp = decoded.copyOfRange(37, 53)
    val relayOrigin = if (selector == 0) null else decoded.copyOfRange(54, 54 + selector).toString(Charsets.UTF_8)
    return RelayPairLink(uuidString(instanceBytes), totp, nonce, caFpTag, caFp, relayOrigin)
}

private fun uuidString(bytes: ByteArray): String {
    require(bytes.size == 16) { "UUID requires 16 bytes" }
    val value = hex(bytes)
    return value.substring(0, 8) + "-" +
        value.substring(8, 12) + "-" +
        value.substring(12, 16) + "-" +
        value.substring(16, 20) + "-" +
        value.substring(20, 32)
}

fun isPrivateOrLinkLocal(a: Int, b: Int): Boolean =
    a == 10 ||
        (a == 172 && b >= 16 && b <= 31) ||
        (a == 192 && b == 168) ||
        (a == 169 && b == 254)

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
