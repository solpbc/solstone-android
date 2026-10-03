// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.audio

import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioPublicationTest {
    @Test
    fun failedRemuxFinalizationPreservesPriorOutputAndCleansTemporaryBytes() {
        val dir = Files.createTempDirectory("remux-publication").toFile()
        try {
            val output = dir.resolve("audio.m4a").apply { writeText("prior-readable-output") }
            assertFailsWith<IOException> {
                publishRemuxOutput(output) {
                    it.writeText("unfinished-output")
                    throw IOException("muxer stop failed")
                }
            }
            assertEquals("prior-readable-output", output.readText())
            assertEquals(listOf("audio.m4a"), dir.listFiles()!!.map { it.name })
            val result = publishRemuxOutput(output) {
                it.writeText("new-output")
                AacRemux(1000, it.length())
            }
            assertEquals(1000L, result.sampleDurationMs)
            assertEquals("new-output", output.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun interruptedMetadataReplacementKeepsCompletePreviousCoordinates() {
        val dir = Files.createTempDirectory("audio-metadata-publication").toFile()
        try {
            val file = dir.resolve("record.txt")
            val info = AudioRecordInfo("rec-test", 1000, "UTC", 0, 0, 300000)
            assertTrue(AudioContinuousSourceEngine.writeAndForceRecordTxt(file, info))
            assertFalse(AudioContinuousSourceEngine.writeAndForceRecordTxt(file, info.copy(sampleDurationMs = 900)) {
                throw IOException("death before metadata publication")
            })
            assertEquals(info, AudioRecordInfo.parse(file.readText()))
            assertTrue(AudioContinuousSourceEngine.writeAndForceRecordTxt(file, info.copy(sampleDurationMs = 900)))
            assertEquals(900L, AudioRecordInfo.parse(file.readText())!!.sampleDurationMs)
            assertEquals(listOf("record.txt"), dir.listFiles()!!.map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }
}
