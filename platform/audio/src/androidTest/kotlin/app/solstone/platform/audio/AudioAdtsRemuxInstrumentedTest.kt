// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.audio

import android.content.Context
import android.media.MediaExtractor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class AudioAdtsRemuxInstrumentedTest {
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var tempDir: File
    private val remuxer = AndroidAacAdtsRemuxer()

    @Before
    fun setUp() {
        tempDir = File(context.cacheDir, "test-remux-${System.currentTimeMillis()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun recordsAacAdtsAndRemuxesCompleteAndTruncatedFiles() {
        val recorderFactory = MediaRecorderFactory()
        val adtsFile = File(tempDir, "record.adts")
        val recording = recorderFactory.create(adtsFile)

        val wallClockStart = System.currentTimeMillis()
        recording.start()
        Thread.sleep(1000L)
        val finishResult = recording.finish()
        val wallClockDuration = System.currentTimeMillis() - wallClockStart

        assertTrue(finishResult is RecordingFinishResult.Success)
        assertTrue(adtsFile.exists() && adtsFile.length() > 0L)

        val m4aFile = File(tempDir, "output.m4a")
        val remuxResult = remuxer.remux(adtsFile, m4aFile)
        assertTrue(remuxResult.sampleDurationMs > 0L)
        assertTrue(m4aFile.exists() && m4aFile.length() > 0L)

        val extractor = MediaExtractor()
        var sampleCount = 0
        try {
            extractor.setDataSource(m4aFile.absolutePath)
            assertTrue(extractor.trackCount > 0)
            extractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(64 * 1024)
            while (extractor.readSampleData(buffer, 0) >= 0) {
                sampleCount++
                extractor.advance()
            }
        } finally {
            extractor.release()
        }
        assertTrue(sampleCount > 0)

        val adtsBytes = adtsFile.readBytes()
        assertTrue(adtsBytes.size > 100)
        val truncatedBytes = adtsBytes.copyOf(adtsBytes.size - 100)
        val truncatedAdtsFile = File(tempDir, "truncated.adts").apply { writeBytes(truncatedBytes) }
        val truncatedM4aFile = File(tempDir, "truncated.m4a")
        val truncatedRemux = remuxer.remux(truncatedAdtsFile, truncatedM4aFile)

        assertTrue(truncatedRemux.sampleDurationMs > 0L)
        assertTrue(truncatedRemux.sampleDurationMs < remuxResult.sampleDurationMs)
        assertTrue(truncatedRemux.sampleDurationMs < wallClockDuration)

        val truncExtractor = MediaExtractor()
        var truncSampleCount = 0
        try {
            truncExtractor.setDataSource(truncatedM4aFile.absolutePath)
            assertTrue(truncExtractor.trackCount > 0)
            truncExtractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(64 * 1024)
            while (truncExtractor.readSampleData(buffer, 0) >= 0) {
                truncSampleCount++
                truncExtractor.advance()
            }
        } finally {
            truncExtractor.release()
        }
        assertTrue(truncSampleCount > 0)
    }

    @Test
    fun remuxNonExistentAdtsReturnsZero() {
        val adts = File(tempDir, "missing.adts")
        val m4a = File(tempDir, "out.m4a")
        val result = remuxer.remux(adts, m4a)
        assertEquals(0L, result.sampleDurationMs)
        assertEquals(0L, result.outputBytes)
        assertFalse(m4a.exists())
    }

    @Test
    fun remuxEmptyAdtsReturnsZero() {
        val adts = File(tempDir, "empty.adts").apply { writeBytes(ByteArray(0)) }
        val m4a = File(tempDir, "out.m4a")
        val result = remuxer.remux(adts, m4a)
        assertEquals(0L, result.sampleDurationMs)
        assertEquals(0L, result.outputBytes)
        assertFalse(m4a.exists())
    }

    @Test
    fun remuxCorruptAdtsReturnsZero() {
        val adts = File(tempDir, "corrupt.adts").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val m4a = File(tempDir, "out.m4a")
        val result = remuxer.remux(adts, m4a)
        assertEquals(0L, result.sampleDurationMs)
        assertEquals(0L, result.outputBytes)
        assertFalse(m4a.exists())
    }
}
