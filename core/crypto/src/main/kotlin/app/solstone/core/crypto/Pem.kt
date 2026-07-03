// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

fun pem(type: String, der: ByteArray): String {
    val encoded = Base64.getEncoder().encodeToString(der)
    val out = StringBuilder()
    out.append("-----BEGIN ").append(type).append("-----\n")
    var index = 0
    while (index < encoded.length) {
        out.append(encoded, index, minOf(encoded.length, index + 64)).append('\n')
        index += 64
    }
    out.append("-----END ").append(type).append("-----\n")
    return out.toString()
}

fun pemToDer(pem: String, type: String): ByteArray {
    val normalized = pem
        .replace("-----BEGIN $type-----", "")
        .replace("-----END $type-----", "")
        .replace(Regex("\\s+"), "")
    return Base64.getDecoder().decode(normalized)
}

fun certificateFromPem(pem: String): X509Certificate {
    val factory = CertificateFactory.getInstance("X.509")
    return factory.generateCertificate(ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII))) as X509Certificate
}

fun certificateFromDer(der: ByteArray): X509Certificate {
    val factory = CertificateFactory.getInstance("X.509")
    return factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
}

fun privateKeyFromPem(pem: String): PrivateKey {
    val der = pemToDer(pem, "PRIVATE KEY")
    val factory = KeyFactory.getInstance("EC")
    return factory.generatePrivate(PKCS8EncodedKeySpec(der))
}
