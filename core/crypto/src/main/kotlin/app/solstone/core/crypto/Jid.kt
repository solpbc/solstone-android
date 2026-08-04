// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import java.nio.ByteBuffer
import java.math.BigInteger
import java.util.UUID

private val JID_HKDF_SALT = "solstone/journal/v1".toByteArray(Charsets.US_ASCII)
private val JID_HKDF_INFO = "solstone/jid/uuidv8/v1".toByteArray(Charsets.US_ASCII)
private val ID_EC_PUBLIC_KEY_OID = byteArrayOf(0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01)
private val P256_OID = byteArrayOf(0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07)
private val P256_P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
private val P256_B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
private val P256_SQRT_EXPONENT = P256_P.add(BigInteger.ONE).shiftRight(2)

enum class JidRefusalKind(val wire: String) {
    NOT_P256("not_p256"),
    INVALID_POINT("invalid_point"),
    MALFORMED_SPKI("malformed_spki"),
}

class JidRefusalException(val kind: JidRefusalKind, message: String) : Exception(message)

fun jidFromCaPem(caPem: String): String = jidFromSpkiDer(certificateFromPem(caPem).publicKey.encoded)

fun jidFromSpkiDer(spkiDer: ByteArray): String {
    val raw = hkdfSha256(canonicalP256Spki(spkiDer), JID_HKDF_SALT, JID_HKDF_INFO, 16)
    raw[6] = ((raw[6].toInt() and 0x0f) or 0x80).toByte()
    raw[8] = ((raw[8].toInt() and 0x3f) or 0x80).toByte()
    val buffer = ByteBuffer.wrap(raw)
    return UUID(buffer.getLong(), buffer.getLong()).toString()
}

internal fun canonicalP256Spki(spkiDer: ByteArray): ByteArray = try {
    val outer = SpkiDerReader(spkiDer)
    val body = SpkiDerReader(outer.read(0x30).content)
    outer.requireExhausted()

    val algorithm = SpkiDerReader(body.read(0x30).content)
    val bitString = body.read(0x03).content
    body.requireExhausted()
    val algorithmOid = algorithm.read(0x06).encoded
    // Non-EC AlgorithmIdentifiers can have a different parameter shape, so classify by
    // their first OID before requiring this sequence to be exhausted.
    if (!algorithmOid.contentEquals(ID_EC_PUBLIC_KEY_OID)) {
        throw JidRefusalException(JidRefusalKind.NOT_P256, "journal jid requires id-ecPublicKey")
    }
    val curveOid = algorithm.read(0x06).encoded
    algorithm.requireExhausted()
    if (!curveOid.contentEquals(P256_OID)) {
        throw JidRefusalException(JidRefusalKind.NOT_P256, "journal jid requires P-256")
    }

    require(bitString.isNotEmpty() && bitString[0] == 0.toByte()) { "invalid SPKI bit string" }
    val point = decodePoint(bitString.copyOfRange(1, bitString.size))
    der(
        0x30,
        concat(
            der(0x30, concat(ID_EC_PUBLIC_KEY_OID, P256_OID)),
            der(0x03, concat(byteArrayOf(0x00), byteArrayOf(0x04), point.x.fixed32(), point.y.fixed32())),
        ),
    )
} catch (refusal: JidRefusalException) {
    throw refusal
} catch (failure: IllegalArgumentException) {
    throw JidRefusalException(JidRefusalKind.MALFORMED_SPKI, failure.message ?: "malformed SPKI")
}

private fun decodePoint(bytes: ByteArray): P256Point {
    if (bytes.size == 1 && bytes[0] == 0.toByte()) {
        throw JidRefusalException(JidRefusalKind.INVALID_POINT, "point at infinity")
    }
    return when (bytes.firstOrNull()?.toInt()?.and(0xff)) {
        0x04 -> {
            if (bytes.size != 65) malformed("invalid uncompressed point length")
            P256Point(unsigned(bytes.copyOfRange(1, 33)), unsigned(bytes.copyOfRange(33, 65))).also(::validatePoint)
        }
        0x02, 0x03 -> {
            if (bytes.size != 33) malformed("invalid compressed point length")
            val x = unsigned(bytes.copyOfRange(1, 33))
            if (x >= P256_P) invalidPoint("point coordinate out of range")
            val rhs = curveRightSide(x)
            var y = rhs.modPow(P256_SQRT_EXPONENT, P256_P)
            if (y.multiply(y).mod(P256_P) != rhs) invalidPoint("compressed point has no square root")
            val odd = bytes[0].toInt() == 0x03
            if (y.testBit(0) != odd) y = P256_P.subtract(y)
            P256Point(x, y)
        }
        else -> malformed("unsupported point encoding")
    }
}

private fun validatePoint(point: P256Point) {
    if (point.x >= P256_P || point.y >= P256_P) invalidPoint("point coordinate out of range")
    if (point.y.multiply(point.y).mod(P256_P) != curveRightSide(point.x)) invalidPoint("point is not on P-256")
}

private fun curveRightSide(x: BigInteger): BigInteger =
    x.multiply(x).multiply(x).subtract(x.multiply(BigInteger.valueOf(3))).add(P256_B).mod(P256_P)

private fun unsigned(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

private fun BigInteger.fixed32(): ByteArray {
    val source = toByteArray().let { if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else it }
    check(source.size <= 32)
    return ByteArray(32).also { source.copyInto(it, 32 - source.size) }
}

private fun malformed(message: String): Nothing = throw JidRefusalException(JidRefusalKind.MALFORMED_SPKI, message)

private fun invalidPoint(message: String): Nothing = throw JidRefusalException(JidRefusalKind.INVALID_POINT, message)

private data class P256Point(val x: BigInteger, val y: BigInteger)
