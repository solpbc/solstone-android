// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import java.security.KeyPair
import java.security.Signature
import javax.security.auth.x500.X500Principal

fun buildCsrPem(label: String, keyPair: KeyPair): String {
    val certificationRequestInfo = der(
        0x30,
        concat(
            der(0x02, byteArrayOf(0x00)),
            X500Principal("CN=" + safeDnValue(label)).encoded,
            keyPair.public.encoded,
            der(0xa0, ByteArray(0)),
        ),
    )
    val signature = Signature.getInstance("SHA256withECDSA")
    signature.initSign(keyPair.private)
    signature.update(certificationRequestInfo)
    val signed = signature.sign()
    val request = der(
        0x30,
        concat(
            certificationRequestInfo,
            der(0x30, ECDSA_WITH_SHA256_OID),
            der(0x03, concat(byteArrayOf(0x00), signed)),
        ),
    )
    return pem("CERTIFICATE REQUEST", request)
}

private fun safeDnValue(raw: String?): String {
    var value = raw ?: "rogbid-watch"
    value = value.replace(Regex("[^A-Za-z0-9_.-]"), "-")
    if (value.isEmpty()) {
        value = "rogbid-watch"
    }
    if (value.length > 64) {
        value = value.substring(0, 64)
    }
    return value
}
