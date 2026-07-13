// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.testing

fun validDirectPairLink(): String {
    val bytes = ByteArray(40)
    bytes[0] = 0x04
    bytes[1] = 0x01
    bytes[2] = 10
    bytes[3] = 0
    bytes[4] = 0
    bytes[5] = 2
    bytes[6] = 0x1d
    bytes[7] = 0xe9.toByte()
    for (index in 8 until bytes.size) {
        bytes[index] = index.toByte()
    }
    return "https://go.solstone.app/p#${crockfordEncode(bytes)}"
}

private fun crockfordEncode(bytes: ByteArray): String {
    val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    val output = StringBuilder()
    var buffer = 0
    var bits = 0
    bytes.forEach { raw ->
        buffer = (buffer shl 8) or (raw.toInt() and 0xff)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            output.append(alphabet[(buffer shr bits) and 31])
            buffer = buffer and ((1 shl bits) - 1)
        }
    }
    if (bits > 0) {
        output.append(alphabet[(buffer shl (5 - bits)) and 31])
    }
    return output.toString()
}
