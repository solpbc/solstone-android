// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenSampleWriterTest {
    @Test fun excludedFramesNeverReachRawBytesIndexOrRecovery() {
        val root = Files.createTempDirectory("screen-admission").toFile()
        val raw = File(root, "capture.h264")
        val index = File(root, "samples.idx")
        var allowed = true
        val gate = ScreenCaptureGate({ allowed })
        val permit = gate.openPortion()!!
        val prefix = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        val excluded = byteArrayOf(0, 0, 0, 1, 0x65, 99)
        try {
            FileOutputStream(raw).use { video -> FileOutputStream(index).use { idx ->
                val writer = ScreenSampleWriter(video, idx, permit::allowsFrame)
                assertTrue(writer.append(ByteBuffer.wrap(prefix), 1000L, 1, false))
                val beforeRaw = raw.readBytes().toList()
                val beforeIndex = index.readText()
                allowed = false
                assertFalse(writer.append(ByteBuffer.wrap(excluded), 2000L, 1, false))
                allowed = true
                assertFalse(writer.append(ByteBuffer.wrap(excluded), 3000L, 1, false))
                assertEquals(beforeRaw, raw.readBytes().toList())
                assertEquals(beforeIndex, index.readText())
            } }
            val recovery = AnnexBParser.validateAndTruncate(raw, index)
            assertEquals(listOf(1000L), recovery.validSamples.map { it.ptsUs })
            assertEquals(prefix.size.toLong(), recovery.truncatedFileSize)
        } finally { root.deleteRecursively() }
    }
}
