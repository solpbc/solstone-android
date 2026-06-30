// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class HkdfTest {
    @Test
    fun derivesRelayPairWindowRkVector() {
        assertContentEquals(
            hexBytes("e34481a4cde647ba9c9fb29a59e18271"),
            deriveRk(hexBytes("0123456789abcdef")),
        )
    }

    @Test
    fun hkdfSha256ExpandsAcrossMultipleBlocks() {
        assertContentEquals(
            hexBytes("e34481a4cde647ba9c9fb29a59e1827137e9fec6d650d068fe626b0edd80a4a70c20761cbc5577ec"),
            hkdfSha256(
                hexBytes("0123456789abcdef"),
                ByteArray(0),
                "spl-pair-window-v1".toByteArray(Charsets.US_ASCII),
                40,
            ),
        )
    }

    @Test
    fun rejectsWrongSLength() {
        assertFailsWith<IllegalArgumentException> {
            deriveRk(ByteArray(7))
        }
    }
}

internal fun hexBytes(value: String): ByteArray {
    val out = ByteArray(value.length / 2)
    for (index in out.indices) {
        out[index] = value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    return out
}
