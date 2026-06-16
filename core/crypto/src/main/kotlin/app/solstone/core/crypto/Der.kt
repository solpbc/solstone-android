// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import java.io.ByteArrayOutputStream

internal val ECDSA_WITH_SHA256_OID = byteArrayOf(
    0x06,
    0x08,
    0x2a,
    0x86.toByte(),
    0x48,
    0xce.toByte(),
    0x3d,
    0x04,
    0x03,
    0x02,
)

internal fun der(tag: Int, content: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(tag)
    writeDerLength(out, content.size)
    out.write(content, 0, content.size)
    return out.toByteArray()
}

internal fun writeDerLength(out: ByteArrayOutputStream, length: Int) {
    if (length < 0x80) {
        out.write(length)
        return
    }
    var bytes = 0
    var value = length
    while (value > 0) {
        bytes += 1
        value = value shr 8
    }
    out.write(0x80 or bytes)
    for (shift in (bytes - 1) * 8 downTo 0 step 8) {
        out.write((length shr shift) and 0xff)
    }
}

internal fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(out, offset)
        offset += part.size
    }
    return out
}
