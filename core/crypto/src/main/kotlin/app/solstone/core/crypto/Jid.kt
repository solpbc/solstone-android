// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import java.nio.ByteBuffer
import java.util.UUID
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers

private val JID_HKDF_SALT = "solstone/journal/v1".toByteArray(Charsets.US_ASCII)
private val JID_HKDF_INFO = "solstone/jid/uuidv8/v1".toByteArray(Charsets.US_ASCII)

class JidRefusalException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun jidFromCaPem(caPem: String): String = jidFromSpkiDer(certificateFromPem(caPem).publicKey.encoded)

fun jidFromSpkiDer(spkiDer: ByteArray): String {
    val raw = hkdfSha256(canonicalP256Spki(spkiDer), JID_HKDF_SALT, JID_HKDF_INFO, 16)
    raw[6] = ((raw[6].toInt() and 0x0f) or 0x80).toByte()
    raw[8] = ((raw[8].toInt() and 0x3f) or 0x80).toByte()
    val buffer = ByteBuffer.wrap(raw)
    return UUID(buffer.getLong(), buffer.getLong()).toString()
}

internal fun canonicalP256Spki(spkiDer: ByteArray): ByteArray = try {
    val parsed = ASN1Primitive.fromByteArray(spkiDer)
    require(parsed.getEncoded(ASN1Encoding.DER).contentEquals(spkiDer)) { "SPKI must be canonical DER" }
    val spki = SubjectPublicKeyInfo.getInstance(parsed)
    requireIdEcPublicKeyWithP256NamedCurve(spki.algorithm)
    val bitString = spki.publicKeyData
    requireZeroUnusedBits(bitString.padBits)
    val uncompressedPoint = decodeAndValidateP256Point(bitString.bytes)
    SubjectPublicKeyInfo(
        AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, SECObjectIdentifiers.secp256r1),
        uncompressedPoint,
    ).getEncoded(ASN1Encoding.DER)
} catch (failure: Exception) {
    throw JidRefusalException(failure.message ?: "invalid journal SPKI", failure)
}

private fun requireIdEcPublicKeyWithP256NamedCurve(algorithm: AlgorithmIdentifier) {
    require(algorithm.algorithm == X9ObjectIdentifiers.id_ecPublicKey) { "journal jid requires id-ecPublicKey" }
    val namedCurve = algorithm.parameters as? ASN1ObjectIdentifier
        ?: throw IllegalArgumentException("journal jid requires named curve parameters")
    require(namedCurve == SECObjectIdentifiers.secp256r1) { "journal jid requires P-256" }
}

private fun requireZeroUnusedBits(padBits: Int) {
    require(padBits == 0) { "journal jid requires zero unused bits" }
}

private fun decodeAndValidateP256Point(encoded: ByteArray): ByteArray {
    when (encoded.firstOrNull()?.toInt()?.and(0xff)) {
        0x04 -> require(encoded.size == 65) { "invalid uncompressed P-256 point length" }
        0x02, 0x03 -> require(encoded.size == 33) { "invalid compressed P-256 point length" }
        else -> throw IllegalArgumentException("unsupported P-256 point encoding")
    }
    val parameters = requireNotNull(ECNamedCurveTable.getByOID(SECObjectIdentifiers.secp256r1))
    val point = parameters.curve.decodePoint(encoded)
    require(!point.isInfinity && point.isValid) { "invalid P-256 point" }
    return point.getEncoded(false)
}
