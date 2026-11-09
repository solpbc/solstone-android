// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.wear

import app.solstone.core.watch.courier.CodecResult
import app.solstone.core.watch.courier.CourierBundleHeader
import app.solstone.core.watch.courier.CourierFilePayload
import app.solstone.core.watch.courier.TimestampedAudioFrame
import app.solstone.core.watch.courier.WatchBundleReceipt
import app.solstone.core.watch.courier.WatchCourierCodec
import app.solstone.core.watch.courier.WatchCourierProtocol
import app.solstone.observer.wear.spool.WatchSpoolStore
import app.solstone.platform.audio.AacAdtsRemuxer
import app.solstone.platform.audio.AacRemux
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WatchSpoolStoreTest {
    @Test
    fun storeAndRetrieveAndVerifyReceiptDeletion() {
        val tempDir = Files.createTempDirectory("watch-spool-test").toFile()
        val store = WatchSpoolStore(tempDir)

        val bundleId = "test-bundle-1"
        val audioBytes = byteArrayOf(1, 2, 3, 4)
        val header = CourierBundleHeader(
            protocolVersion = WatchCourierProtocol.PROTOCOL_VERSION,
            bundleId = bundleId,
            day = "20261109",
            segment = "100000_300",
            startEpochMs = 1762682400000L,
            endEpochMs = 1762682700000L,
            zoneId = "America/Denver",
            utcOffsetSeconds = -25200,
            sealGeneration = 1L,
        )
        val files = listOf(
            CourierFilePayload(
                name = WatchCourierProtocol.FILE_NAME_AUDIO,
                mediaType = WatchCourierProtocol.MEDIA_TYPE_AUDIO,
                sha256 = WatchCourierCodec.sha256Hex(audioBytes),
                byteSize = audioBytes.size.toLong(),
                bytes = audioBytes,
            ),
        )
        val baos = ByteArrayOutputStream()
        WatchCourierCodec.encodeBundle(header, files, baos)
        val bundleBytes = baos.toByteArray()

        val storedFile = store.storeBundle(bundleId, bundleBytes)
        assertTrue(storedFile.exists())
        assertEquals(1, store.listPendingFiles().size)

        val staged = store.getStagedBundle(storedFile)
        assertNotNull(staged)
        assertEquals(bundleId, staged.bundleId)
        assertEquals(1L, staged.sealGeneration)

        // Invalid receipt (different bundleId) must not delete
        val invalidReceipt = WatchBundleReceipt(
            protocolVersion = WatchCourierProtocol.PROTOCOL_VERSION,
            bundleId = "wrong-bundle",
            sha256 = staged.sha256,
            sealGeneration = 1L,
            segmentId = "seg-1",
            committedAt = 1000L,
        )
        assertFalse(store.deleteBundle(storedFile, invalidReceipt))
        assertTrue(storedFile.exists())

        // Valid receipt deletes the bundle
        val validReceipt = WatchBundleReceipt(
            protocolVersion = WatchCourierProtocol.PROTOCOL_VERSION,
            bundleId = bundleId,
            sha256 = staged.sha256,
            sealGeneration = 1L,
            segmentId = "seg-1",
            committedAt = 1000L,
        )
        assertTrue(store.deleteBundle(storedFile, validReceipt))
        assertFalse(storedFile.exists())
        assertEquals(0, store.listPendingFiles().size)
    }

    @Test
    fun uncleanSessionWithValidFramesRecoversPartialWithRealLength() {
        val tempDir = Files.createTempDirectory("watch-spool-recover-test").toFile()
        val store1 = WatchSpoolStore(tempDir, processGeneration = "p1")

        val sessionId = store1.startOpenSession(ZoneId.of("UTC"))
        val startZdt = java.time.ZonedDateTime.of(2026, 11, 9, 10, 0, 0, 0, ZoneId.of("UTC"))
        val startEpoch = startZdt.toInstant().toEpochMilli()

        // Append 47 valid frames (47 * 64ms = 3008 ms ~ 3 seconds)
        for (i in 0 until 47) {
            val frameBytes = createValidAdtsFrame(payloadSize = 50)
            store1.appendOpenFrame(
                TimestampedAudioFrame(
                    timestampEpochMs = startEpoch + (i * 64L),
                    data = frameBytes,
                    durationMs = 64L,
                ),
                ZoneId.of("UTC"),
            )
        }

        // Append truncated trailing bytes (less than 7 bytes) to verify truncated frame is discarded
        val framesFile = File(tempDir, "sessions/$sessionId/raw_frames.adts")
        FileOutputStream(framesFile, true).use { fos ->
            fos.write(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x60.toByte()))
            fos.fd.sync()
        }

        // Simulate crash without clean shutdown. A second store with a different process generation recovers abandoned sessions.
        val fakeRemuxer = AacAdtsRemuxer { adts, m4a ->
            m4a.writeBytes(adts.readBytes())
            AacRemux(sampleDurationMs = 3008L, outputBytes = adts.length())
        }
        val store2 = WatchSpoolStore(tempDir, remuxer = fakeRemuxer, processGeneration = "p2")
        store2.recoverAbandonedSessions()

        assertTrue(store2.processLossDetected, "Must record process loss on unclean recovery")
        val staged = store2.listStagedBundles()
        assertEquals(1, staged.size, "Must recover partial bundle from valid frames")

        val bundleFile = staged.first().first
        val decoded = WatchCourierCodec.decodeBundle(bundleFile.readBytes().inputStream())
        assertTrue(decoded is CodecResult.Success)
        val header = decoded.value.header
        // Real length is 3 seconds (3008 ms), segment must be 100000_3, not _300
        assertEquals("100000_3", header.segment)
        assertEquals("20261109", header.day)
        assertEquals(startEpoch, header.startEpochMs)
    }

    @Test
    fun uncleanSessionWithUnusableBytesMarksLostAndRecordsProcessLoss() {
        val tempDir = Files.createTempDirectory("watch-spool-unusable-test").toFile()
        val store1 = WatchSpoolStore(tempDir, processGeneration = "p1")

        val sessionId = store1.startOpenSession(ZoneId.of("UTC"))
        // Write only corrupt/unusable bytes
        val framesFile = File(tempDir, "sessions/$sessionId/raw_frames.adts")
        FileOutputStream(framesFile, true).use { fos ->
            fos.write(byteArrayOf(1, 2, 3, 4))
            fos.fd.sync()
        }

        // Simulate crash without clean shutdown. A second store with a different process generation recovers abandoned sessions.
        val store2 = WatchSpoolStore(tempDir, processGeneration = "p2")
        store2.recoverAbandonedSessions()

        assertTrue(store2.processLossDetected, "Must record process loss when bytes unusable")
        assertTrue(store2.hasLostMarker(), "Must write lost marker when bytes unusable")
        assertEquals(1, store2.listLostMarkers().size)
        assertTrue(store2.listPendingFiles().isEmpty(), "Must not recover bundle from unusable bytes")
    }

    @Test
    fun sameProcessStoreDoesNotRecoverLiveSession() {
        val tempDir = Files.createTempDirectory("watch-spool-same-process-test").toFile()
        val gen = "p1"
        val storeA = WatchSpoolStore(tempDir, processGeneration = gen)
        val sessionId = storeA.startOpenSession(ZoneId.of("UTC"))

        val frameBytes = createValidAdtsFrame(payloadSize = 50)
        storeA.appendOpenFrame(
            TimestampedAudioFrame(
                timestampEpochMs = 1762682400000L,
                data = frameBytes,
                durationMs = 64L,
            ),
            ZoneId.of("UTC"),
        )

        val storeB = WatchSpoolStore(tempDir, processGeneration = gen)
        storeB.recoverAbandonedSessions()

        assertTrue(storeB.listPendingFiles().isEmpty(), "No bundle was created")
        assertTrue(storeB.listStagedBundles().isEmpty(), "No staged bundles")
        assertFalse(storeB.processLossDetected, "processLossDetected is false on B")

        val sessionFrames = File(tempDir, "sessions/$sessionId/raw_frames.adts")
        assertTrue(sessionFrames.exists(), "Live frames are still in A's session dir")
        assertTrue(sessionFrames.length() > 0, "Live frames are preserved")
    }

    @Test
    fun rotateRetireLeavesNextWindowBytes() {
        val tempDir = Files.createTempDirectory("watch-spool-rotate-retire-test").toFile()
        val store = WatchSpoolStore(tempDir)

        val w1Id = store.startOpenSession(ZoneId.of("UTC"))
        val frame1 = createValidAdtsFrame(payloadSize = 30)
        store.appendOpenFrame(
            TimestampedAudioFrame(
                timestampEpochMs = 1762682400000L,
                data = frame1,
                durationMs = 64L,
            ),
            ZoneId.of("UTC"),
        )

        val rotatedId = store.rotateOpenSession(ZoneId.of("UTC"))
        assertEquals(w1Id, rotatedId)
        val w2Id = store.readLiveSessionId()!!

        val frame2 = createValidAdtsFrame(payloadSize = 40)
        store.appendOpenFrame(
            TimestampedAudioFrame(
                timestampEpochMs = 1762682400000L + 64L,
                data = frame2,
                durationMs = 64L,
            ),
            ZoneId.of("UTC"),
        )

        val retired = store.retireSession(w1Id)
        assertTrue(retired, "retireSession(w1Id) must succeed")

        val w1Dir = File(tempDir, "sessions/$w1Id")
        assertFalse(w1Dir.exists(), "Window-1 directory is gone")

        val w2Dir = File(tempDir, "sessions/$w2Id")
        assertTrue(w2Dir.exists(), "Window-2 directory remains")
        val w2Frames = File(w2Dir, "raw_frames.adts")
        assertTrue(w2Frames.exists() && w2Frames.length() > 0, "Window-2 bytes remain")

        assertTrue(store.listPendingFiles().isEmpty(), "listPendingFiles() is empty (retire is not a seal)")
    }

    private fun createValidAdtsFrame(payloadSize: Int = 100): ByteArray {
        val frameLength = 7 + payloadSize
        val packet = ByteArray(frameLength)
        packet[0] = 0xFF.toByte()
        packet[1] = 0xF1.toByte() // sync + protection absent
        packet[2] = 0x60.toByte() // 16000 Hz
        packet[3] = ((frameLength ushr 11) and 0x03).toByte()
        packet[4] = ((frameLength ushr 3) and 0xFF).toByte()
        packet[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
        packet[6] = 0xFC.toByte()
        for (i in 7 until frameLength) {
            packet[i] = (i % 256).toByte()
        }
        return packet
    }
}
