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

class DirectPairLink(
    val host: String,
    val port: Int,
    val nonce: String,
    val caFingerprintPrefix: ByteArray,
) {
    fun endpoint(): DirectEndpoint = DirectEndpoint(host, if (port <= 0) DEFAULT_DIRECT_PORT else port)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DirectPairLink) return false
        return host == other.host &&
            port == other.port &&
            nonce == other.nonce &&
            caFingerprintPrefix.contentEquals(other.caFingerprintPrefix)
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port
        result = 31 * result + nonce.hashCode()
        result = 31 * result + caFingerprintPrefix.contentHashCode()
        return result
    }

    override fun toString(): String =
        "DirectPairLink(host=$host, port=$port, nonce=$nonce, caFingerprintPrefix=${caFingerprintPrefix.contentToString()})"
}

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
    val url = URL(pairLink.trim())
    if (url.protocol != "https" || url.host !in RECOGNIZED_PAIR_HOSTS || url.path != PAIR_LINK_PATH) {
        throw IllegalArgumentException("not a solstone pair link")
    }
    val fragment = url.ref
    if (fragment == null || fragment.isEmpty()) {
        throw IllegalArgumentException("pair link missing fragment")
    }
    val decoded = decodeCrockford32(fragment)
    // The decoded blob bytes are authoritative; host/path only route to the pair payload.
    if (decoded.size != 40 || decoded[0] != 0x04.toByte() || decoded[1] != 0x01.toByte()) {
        throw IllegalArgumentException("unsupported pair link payload")
    }
    val a = decoded[2].toInt() and 0xff
    val b = decoded[3].toInt() and 0xff
    val c = decoded[4].toInt() and 0xff
    val d = decoded[5].toInt() and 0xff
    if (!isPrivateOrLinkLocal(a, b)) {
        throw IllegalArgumentException("pair link is not local/private IPv4")
    }
    val host = "$a.$b.$c.$d"
    val port = ((decoded[6].toInt() and 0xff) shl 8) or (decoded[7].toInt() and 0xff)
    val nonceBytes = decoded.copyOfRange(8, 24)
    val caFp = decoded.copyOfRange(24, 40)
    return DirectPairLink(host, port, hex(nonceBytes), caFp)
}

fun isPrivateOrLinkLocal(a: Int, b: Int): Boolean =
    a == 10 ||
        (a == 172 && b >= 16 && b <= 31) ||
        (a == 192 && b == 168) ||
        (a == 169 && b == 254)

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
