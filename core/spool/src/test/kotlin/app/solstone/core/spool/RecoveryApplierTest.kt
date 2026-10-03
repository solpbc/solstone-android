// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import app.solstone.core.model.SegmentKey
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.wireKeys
import app.solstone.core.sources.MAIN_STREAM
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryApplierTest {
    @Test
    fun applyRecoveryActionsFinalizesValidDraft() {
        val baseDir = Files.createTempDirectory("recovery-finalize")
        try {
            val segment = emptySegment()
            val draftDir = baseDir.resolve(".draft").resolve(segment.key.day).resolve(segment.stream).resolve(segment.key.segment)
            Files.createDirectories(draftDir)
            Files.write(
                draftDir.resolve("manifest"),
                serializeManifest(segment, app.solstone.core.model.BundleManifest(segment.key, emptyList(), emptyList()))
                    .toByteArray(StandardCharsets.UTF_8),
            )

            val interruption = FakeInterruption()
            val events = applyRecoveryActions(RecoveryScanner(baseDir).scan(nowEpochMs = 10L), interruption, interruption, interruption)
            val finalDir = baseDir.resolve(segment.key.day).resolve(segment.stream).resolve(segment.key.segment)

            assertTrue(Files.isRegularFile(finalDir.resolve("manifest")))
            assertFalse(Files.exists(draftDir))
            assertEquals(listOf("finalized_segment"), events.map { it.kind })
            assertEquals(0, interruption.marks)
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun applyRecoveryActionsPreservesExistingFinalAndDiscardsRedundantDraft() {
        val baseDir = Files.createTempDirectory("recovery-existing-final")
        try {
            val segment = emptySegment()
            val draftDir = baseDir.resolve(".draft").resolve(segment.key.day).resolve(segment.stream).resolve(segment.key.segment)
            val finalDir = baseDir.resolve(segment.key.day).resolve(segment.stream).resolve(segment.key.segment)
            val sentinel = "existing-final-manifest"
            Files.createDirectories(draftDir)
            Files.createDirectories(finalDir)
            Files.write(
                draftDir.resolve("manifest"),
                serializeManifest(segment, app.solstone.core.model.BundleManifest(segment.key, emptyList(), emptyList()))
                    .toByteArray(StandardCharsets.UTF_8),
            )
            Files.write(finalDir.resolve("manifest"), sentinel.toByteArray(StandardCharsets.UTF_8))

            val interruption = FakeInterruption()
            val events = applyRecoveryActions(RecoveryScanner(baseDir).scan(nowEpochMs = 10L), interruption, interruption, interruption)

            assertEquals(sentinel, String(Files.readAllBytes(finalDir.resolve("manifest")), StandardCharsets.UTF_8))
            // Unverified final -> draft kept and markUnresolved was required
            assertTrue(Files.exists(draftDir))
            assertEquals(0, interruption.marks)
            assertEquals(
                listOf(SpoolRecoveryEvent("partial_segment", segment.wireKeys.endEpochMs, "final already exists")),
                events,
            )
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun applyRecoveryActionsVerifiedEqualFinalDeletesDraftWithoutMark() {
        val baseDir = Files.createTempDirectory("recovery-verified-final")
        try {
            val segment = emptySegment()
            val draftDir = baseDir.resolve(".draft").resolve(segment.key.day).resolve(segment.stream).resolve(segment.key.segment)
            val finalDir = baseDir.resolve(segment.key.day).resolve(segment.stream).resolve(segment.key.segment)
            val manifestBytes = serializeManifest(segment, app.solstone.core.model.BundleManifest(segment.key, emptyList(), emptyList()))
                .toByteArray(StandardCharsets.UTF_8)
            Files.createDirectories(draftDir)
            Files.createDirectories(finalDir)
            Files.write(draftDir.resolve("manifest"), manifestBytes)
            Files.write(finalDir.resolve("manifest"), manifestBytes)

            val interruption = FakeInterruption()
            val events = applyRecoveryActions(RecoveryScanner(baseDir).scan(nowEpochMs = 10L), interruption, interruption, interruption)

            assertEquals(manifestBytes.decodeToString(), String(Files.readAllBytes(finalDir.resolve("manifest")), StandardCharsets.UTF_8))
            assertFalse(Files.exists(draftDir))
            assertEquals(0, interruption.marks)
            assertEquals(
                listOf(SpoolRecoveryEvent("partial_segment", segment.wireKeys.endEpochMs, "final already exists")),
                events,
            )
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun applyRecoveryActionsMatchingManifestDifferentPayloadBytesKeepsDraftAndMarks() {
        val baseDir = Files.createTempDirectory("recovery-payload-mismatch")
        try {
            val segment = emptySegment()
            val draftDir = baseDir.resolve(".draft").resolve(segment.key.day).resolve(segment.stream).resolve(segment.key.segment)
            val finalDir = baseDir.resolve(segment.key.day).resolve(segment.stream).resolve(segment.key.segment)
            val draftBytes = "payload-draft".toByteArray(StandardCharsets.UTF_8)
            val finalBytes = "payload-final".toByteArray(StandardCharsets.UTF_8)
            Files.createDirectories(draftDir)
            Files.createDirectories(finalDir)
            Files.write(draftDir.resolve("audio.m4a"), draftBytes)
            Files.write(finalDir.resolve("audio.m4a"), finalBytes)

            val fileEntry = app.solstone.core.model.BundleFile(
                sourceId = "audio",
                name = "audio.m4a",
                sha256 = app.solstone.core.segment.sha256(draftDir.resolve("audio.m4a")),
                byteSize = draftBytes.size.toLong(),
                mediaType = "audio/mp4",
                captureStartEpochMs = BASE_EPOCH_MS,
                captureEndEpochMs = BASE_EPOCH_MS + 1000L,
            )
            val manifestBytes = serializeManifest(segment, app.solstone.core.model.BundleManifest(segment.key, listOf(fileEntry), emptyList()))
                .toByteArray(StandardCharsets.UTF_8)
            Files.write(draftDir.resolve("manifest"), manifestBytes)
            Files.write(finalDir.resolve("manifest"), manifestBytes)

            val interruption = FakeInterruption()
            val events = applyRecoveryActions(RecoveryScanner(baseDir).scan(nowEpochMs = 10L), interruption, interruption, interruption)

            assertTrue(Files.exists(draftDir), "draft should be kept on payload byte mismatch")
            assertEquals(1, interruption.marks)
            assertEquals(
                listOf(SpoolRecoveryEvent("partial_segment", segment.wireKeys.endEpochMs, "final already exists")),
                events,
            )
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun applyRecoveryActionsMatchingIdentityAndPayloadBytesDeletesDraftWithoutMark() {
        val baseDir = Files.createTempDirectory("recovery-payload-match")
        try {
            val segment = emptySegment()
            val draftDir = baseDir.resolve(".draft").resolve(segment.key.day).resolve(segment.stream).resolve(segment.key.segment)
            val finalDir = baseDir.resolve(segment.key.day).resolve(segment.stream).resolve(segment.key.segment)
            val payloadBytes = "identical-payload".toByteArray(StandardCharsets.UTF_8)
            Files.createDirectories(draftDir)
            Files.createDirectories(finalDir)
            Files.write(draftDir.resolve("audio.m4a"), payloadBytes)
            Files.write(finalDir.resolve("audio.m4a"), payloadBytes)

            val fileEntry = app.solstone.core.model.BundleFile(
                sourceId = "audio",
                name = "audio.m4a",
                sha256 = app.solstone.core.segment.sha256(draftDir.resolve("audio.m4a")),
                byteSize = payloadBytes.size.toLong(),
                mediaType = "audio/mp4",
                captureStartEpochMs = BASE_EPOCH_MS,
                captureEndEpochMs = BASE_EPOCH_MS + 1000L,
            )
            val manifestBytes = serializeManifest(segment, app.solstone.core.model.BundleManifest(segment.key, listOf(fileEntry), emptyList()))
                .toByteArray(StandardCharsets.UTF_8)
            Files.write(draftDir.resolve("manifest"), manifestBytes)
            Files.write(finalDir.resolve("manifest"), manifestBytes)

            val interruption = FakeInterruption()
            val events = applyRecoveryActions(RecoveryScanner(baseDir).scan(nowEpochMs = 10L), interruption, interruption, interruption)

            assertFalse(Files.exists(draftDir), "draft should be deleted on payload byte match")
            assertEquals(0, interruption.marks)
            assertEquals(
                listOf(SpoolRecoveryEvent("partial_segment", segment.wireKeys.endEpochMs, "final already exists")),
                events,
            )
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun recoveryFinalizesSuffixedDraftLeafToSameFinalLeaf() {
        val baseDir = Files.createTempDirectory("recovery-suffixed-finalize")
        try {
            val segment = emptySegment()
            val suffixedLeaf = "${segment.key.segment}__ws${segment.wireKeys.startEpochMs + 3_600_000L}"
            val draftDir = baseDir.resolve(".draft").resolve(segment.key.day).resolve(segment.stream).resolve(suffixedLeaf)
            val bareFinalDir = baseDir.resolve(segment.key.day).resolve(segment.stream).resolve(segment.key.segment)
            val suffixedFinalDir = baseDir.resolve(segment.key.day).resolve(segment.stream).resolve(suffixedLeaf)
            Files.createDirectories(draftDir)
            Files.createDirectories(bareFinalDir)
            Files.write(bareFinalDir.resolve("manifest"), "bare-final".toByteArray(StandardCharsets.UTF_8))
            Files.write(
                draftDir.resolve("manifest"),
                serializeManifest(segment, app.solstone.core.model.BundleManifest(segment.key, emptyList(), emptyList()))
                    .toByteArray(StandardCharsets.UTF_8),
            )

            val interruption = FakeInterruption()
            val events = applyRecoveryActions(RecoveryScanner(baseDir).scan(nowEpochMs = 10L), interruption, interruption, interruption)

            assertTrue(Files.exists(bareFinalDir.resolve("manifest")))
            assertTrue(Files.exists(suffixedFinalDir.resolve("manifest")))
            assertFalse(Files.exists(draftDir))
            assertEquals(listOf("finalized_segment"), events.map { it.kind })
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun applyRecoveryActionsDiscardsInvalidDraftAndReturnsEvent() {
        val baseDir = Files.createTempDirectory("recovery-discard")
        try {
            val draftDir = baseDir.resolve(".draft").resolve("20260304").resolve(MAIN_STREAM).resolve("bad")
            Files.createDirectories(draftDir)

            val interruption = FakeInterruption()
            val events = applyRecoveryActions(RecoveryScanner(baseDir).scan(nowEpochMs = 10L), interruption, interruption, interruption)

            assertFalse(Files.exists(draftDir))
            assertEquals(0, interruption.marks)
            assertEquals(listOf(SpoolRecoveryEvent("partial_segment", 10L, "missing manifest")), events)
        } finally {
            baseDir.deleteRecursively()
        }
    }

    private class FakeInterruption(var succeed: Boolean = true) : UnresolvedInterruption {
        var marks = 0
        override fun markUnresolved(): Boolean {
            marks += 1
            return succeed
        }
        override fun isUnresolved(): Boolean = marks > 0
    }

    private fun emptySegment(): SealedSegment {
        val keys = wireKeys(BASE_EPOCH_MS, BASE_EPOCH_MS, ZoneId.of("UTC"))
        return SealedSegment(
            stream = MAIN_STREAM,
            key = SegmentKey(keys.day, keys.segment),
            wireKeys = keys,
            payloads = emptyList(),
            gaps = emptyList(),
        )
    }

    private fun Path.deleteRecursively() {
        Files.walk(this).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private companion object {
        const val BASE_EPOCH_MS = 1_772_582_400_000L
    }
}
