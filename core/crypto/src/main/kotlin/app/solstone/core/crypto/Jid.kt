// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

private val JID_HKDF_SALT = "solstone/journal/v1".toByteArray(Charsets.US_ASCII)
private val JID_HKDF_INFO = "solstone/jid/uuidv8/v1".toByteArray(Charsets.US_ASCII)

fun jidFromSpki(caPem: String): String {
    val keyFactory = KeyFactory.getInstance("EC")
    val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(certificateFromPem(caPem).publicKey.encoded))
    if (publicKey !is ECPublicKey || !isP256(publicKey.params)) {
        throw IllegalArgumentException("journal jid requires an EC P-256 public-key SPKI")
    }
    val raw = hkdfSha256(publicKey.encoded, JID_HKDF_SALT, JID_HKDF_INFO, 16)
    raw[6] = ((raw[6].toInt() and 0x0f) or 0x80).toByte()
    raw[8] = ((raw[8].toInt() and 0x3f) or 0x80).toByte()
    val buffer = ByteBuffer.wrap(raw)
    return UUID(buffer.getLong(), buffer.getLong()).toString()
}

private fun isP256(params: ECParameterSpec): Boolean {
    val p256 = AlgorithmParameters.getInstance("EC")
    p256.init(ECGenParameterSpec("secp256r1"))
    val expected = p256.getParameterSpec(ECParameterSpec::class.java)
    return params.curve == expected.curve &&
        params.generator == expected.generator &&
        params.order == expected.order &&
        params.cofactor == expected.cofactor
}
