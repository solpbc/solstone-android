// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val SHA256_DIGEST_BYTES = 32
private val RK_INFO = "spl-pair-window-v1".toByteArray(Charsets.US_ASCII)

fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length >= 0) { "HKDF length must not be negative" }
    require(length <= 255 * SHA256_DIGEST_BYTES) { "HKDF length too large" }
    val extractSalt = if (salt.isEmpty()) ByteArray(SHA256_DIGEST_BYTES) else salt
    val prk = hmacSha256(extractSalt, ikm)
    val okm = ByteArray(length)
    var generated = 0
    var counter = 1
    var block = ByteArray(0)
    while (generated < length) {
        block = hmacSha256(prk, block + info + counter.toByte())
        val copy = minOf(block.size, length - generated)
        block.copyInto(okm, generated, 0, copy)
        generated += copy
        counter += 1
    }
    return okm
}

fun deriveRk(s: ByteArray): ByteArray {
    require(s.size == 8) { "S must be 8 bytes" }
    return hkdfSha256(s, ByteArray(0), RK_INFO, 16)
}

private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}
