// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class WatchCourierBoundaryTest {
    private val start = Instant.parse("2026-11-09T17:00:00Z").toEpochMilli()
    private val header = CourierBundleHeader(
        protocolVersion = 1,
        bundleId = "recording-1",
        day = "20261109",
        segment = "100000_1",
        startEpochMs = start,
        endEpochMs = start + 1000,
        zoneId = "America/Denver",
        utcOffsetSeconds = -25200,
        sealGeneration = 1,
    )
    private val audio = byteArrayOf(11, 22, 33)
    private val file = CourierFilePayload(
        name = "audio.m4a",
        mediaType = "audio/mp4",
        sha256 = WatchCourierCodec.sha256Hex(audio),
        byteSize = 3,
        bytes = audio,
    )

    private fun packet(h: CourierBundleHeader = header, files: List<CourierFilePayload> = listOf(file)): ByteArray =
        ByteArrayOutputStream().also { WatchCourierCodec.encodeBundle(h, files, it) }.toByteArray()

    @Test
    fun validClockAndAudioRoundTrip() {
        val decoded = assertIs<CodecResult.Success<DecodedBundle>>(
            WatchCourierCodec.decodeBundle(packet().inputStream()),
        ).value
        assertEquals(header, decoded.header)
        assertEquals(file, decoded.files.single())
    }

    @Test
    fun refusesPathComponentsAndInconsistentRecordingClocks() {
        val invalid = listOf(
            header.copy(day = "../../escape"),
            header.copy(day = "20260230"),
            header.copy(segment = "../escape"),
            header.copy(bundleId = "../escape"),
            header.copy(day = "20261110"),
            header.copy(segment = "110000_1"),
            header.copy(segment = "100000_300"),
            header.copy(endEpochMs = start),
            header.copy(sealGeneration = 0),
            header.copy(zoneId = "not/a/timezone"),
            header.copy(utcOffsetSeconds = 999999),
            header.copy(bundleId = "x".repeat(4096)),
        )
        for (h in invalid) {
            assertIs<CodecResult.Malformed>(WatchCourierCodec.decodeBundle(packet(h).inputStream()), h.toString())
        }
    }

    @Test
    fun receiptDigestBindsRecordingMetadataAndSealGeneration() {
        fun digest(h: CourierBundleHeader) = assertIs<CodecResult.Success<DecodedBundle>>(
            WatchCourierCodec.decodeBundle(packet(h).inputStream()),
        ).value.bundleSha256
        val original = digest(header)
        assertNotEquals(original, digest(header.copy(sealGeneration = 2)))
        assertNotEquals(original, digest(header.copy(
            day = "20261110",
            startEpochMs = start + 86_400_000,
            endEpochMs = start + 86_401_000,
        )))
    }

    @Test
    fun refusesMissingAudioAndUnexpectedMediaTypes() {
        val locationOnly = file.copy(name = "location.jsonl", mediaType = "application/x-jsonlines")
        assertIs<CodecResult.Malformed>(WatchCourierCodec.decodeBundle(packet(files = listOf(locationOnly)).inputStream()))
        assertIs<CodecResult.Malformed>(WatchCourierCodec.decodeBundle(packet(files = listOf(file.copy(mediaType = "text/html"))).inputStream()))
    }

    @Test
    fun refusesTrailingBytesAndOversizedReceiptFields() {
        assertIs<CodecResult.Malformed>(WatchCourierCodec.decodeBundle((packet() + byteArrayOf(7)).inputStream()))
        val receipt = WatchBundleReceipt(1, "recording-1", "a".repeat(64), 1, "20261109/watch/100000_1", 123)
        val valid = WatchCourierCodec.encodeReceipt(receipt)
        assertIs<CodecResult.Success<WatchBundleReceipt>>(WatchCourierCodec.decodeReceipt(valid))
        assertIs<CodecResult.Malformed>(WatchCourierCodec.decodeReceipt(valid + byteArrayOf(7)))
        val oversized = WatchCourierCodec.encodeReceipt(receipt.copy(bundleId = "x".repeat(4096)))
        assertIs<CodecResult.Malformed>(WatchCourierCodec.decodeReceipt(oversized))
    }
}
