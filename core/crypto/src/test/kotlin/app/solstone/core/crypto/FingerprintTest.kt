// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FingerprintTest {
    @Test
    fun knownVectors() {
        assertEquals("", hex(ByteArray(0)))
        assertEquals("000fa5ff", hex(byteArrayOf(0x00, 0x0f, 0xa5.toByte(), 0xff.toByte())))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))
        assertTrue(startsWith(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertFalse(startsWith(byteArrayOf(1), byteArrayOf(1, 2)))
        assertFalse(startsWith(byteArrayOf(1, 2), byteArrayOf(1, 3)))
    }
}
