// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WatchCourierCodecTest {
    private val captureStart = Instant.parse("2026-11-09T17:00:00Z").toEpochMilli()

    @Test
    fun roundtripBundle() {
        val audioBytes = byteArrayOf(10, 20, 30, 40)
        val locationBytes = "{\"platform\":\"wearos\"}\n".toByteArray()

        val header = CourierBundleHeader(
            protocolVersion = WatchCourierProtocol.PROTOCOL_VERSION,
            bundleId = "test-bundle-1",
            day = "20261109",
            segment = "100000_300",
            startEpochMs = captureStart,
            endEpochMs = captureStart + 300_000L,
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
            CourierFilePayload(
                name = WatchCourierProtocol.FILE_NAME_LOCATION,
                mediaType = WatchCourierProtocol.MEDIA_TYPE_LOCATION,
                sha256 = WatchCourierCodec.sha256Hex(locationBytes),
                byteSize = locationBytes.size.toLong(),
                bytes = locationBytes,
            ),
        )

        val baos = ByteArrayOutputStream()
        WatchCourierCodec.encodeBundle(header, files, baos)

        val decoded = WatchCourierCodec.decodeBundle(ByteArrayInputStream(baos.toByteArray()))
        assertIs<CodecResult.Success<DecodedBundle>>(decoded)

        val bundle = decoded.value
        assertEquals(header, bundle.header)
        assertEquals(files.size, bundle.files.size)
        assertEquals(files[0].name, bundle.files[0].name)
        assertEquals(files[0].sha256, bundle.files[0].sha256)
        assertTrue(files[0].bytes.contentEquals(bundle.files[0].bytes))
        assertEquals(files[1].name, bundle.files[1].name)
        assertEquals(files[1].sha256, bundle.files[1].sha256)
        assertTrue(files[1].bytes.contentEquals(bundle.files[1].bytes))
    }

    @Test
    fun roundtripReceipt() {
        val receipt = WatchBundleReceipt(
            protocolVersion = WatchCourierProtocol.PROTOCOL_VERSION,
            bundleId = "bundle-xyz",
            sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            sealGeneration = 42L,
            segmentId = "20261109/watch/100000_300",
            committedAt = 123456789L,
        )

        val bytes = WatchCourierCodec.encodeReceipt(receipt)
        val decoded = WatchCourierCodec.decodeReceipt(bytes)
        assertIs<CodecResult.Success<WatchBundleReceipt>>(decoded)
        assertEquals(receipt, decoded.value)
    }

    @Test
    fun decodeBundleRejectsVersionMismatch() {
        val header = CourierBundleHeader(
            protocolVersion = 999, // Future version
            bundleId = "bundle-1",
            day = "20261109",
            segment = "170000_300",
            startEpochMs = captureStart,
            endEpochMs = captureStart + 300_000L,
            zoneId = "UTC",
            utcOffsetSeconds = 0,
            sealGeneration = 1L,
        )
        val files = listOf(
            CourierFilePayload(
                name = WatchCourierProtocol.FILE_NAME_AUDIO,
                mediaType = WatchCourierProtocol.MEDIA_TYPE_AUDIO,
                sha256 = WatchCourierCodec.sha256Hex(byteArrayOf(1)),
                byteSize = 1,
                bytes = byteArrayOf(1),
            ),
        )
        val baos = ByteArrayOutputStream()
        WatchCourierCodec.encodeBundle(header, files, baos)

        val result = WatchCourierCodec.decodeBundle(ByteArrayInputStream(baos.toByteArray()))
        assertIs<CodecResult.VersionMismatch>(result)
        assertEquals(999, result.version)
    }

    @Test
    fun decodeBundleRejectsMalformedBytes() {
        val garbage = byteArrayOf(0, 1, 2, 3)
        val result = WatchCourierCodec.decodeBundle(ByteArrayInputStream(garbage))
        assertIs<CodecResult.Malformed>(result)
    }

    @Test
    fun decodeReceiptRejectsMalformedBytes() {
        val garbage = byteArrayOf(9, 9, 9)
        val result = WatchCourierCodec.decodeReceipt(garbage)
        assertIs<CodecResult.Malformed>(result)
    }
}
