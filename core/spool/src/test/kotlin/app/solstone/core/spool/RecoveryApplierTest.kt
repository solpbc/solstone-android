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
import kotlin.test.assertIs
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

            val applied = run(baseDir)
            val finalDir = baseDir.resolve(segment.key.day).resolve(segment.stream).resolve(segment.key.segment)

            assertTrue(Files.isRegularFile(finalDir.resolve("manifest")))
            assertFalse(Files.exists(draftDir))
            assertEquals(listOf("finalized_segment"), applied.events.map { it.kind })
            assertEquals(segment.key.segment, applied.events.single().detail)
            assertFalse(applied.events.single().detail!!.contains("/"))
            assertEquals(listOf("delivered"), rows(applied.gaps).map { it.reason })
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

            val applied = run(baseDir)

            assertEquals(sentinel, String(Files.readAllBytes(finalDir.resolve("manifest")), StandardCharsets.UTF_8))
            assertTrue(Files.exists(draftDir))
            assertEquals("divergent final", applied.events.single().detail)
            assertEquals(1, applied.unknown.size)
            assertTrue(rows(applied.gaps).isEmpty())
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

            val applied = run(baseDir)

            assertEquals(manifestBytes.decodeToString(), String(Files.readAllBytes(finalDir.resolve("manifest")), StandardCharsets.UTF_8))
            assertFalse(Files.exists(draftDir))
            assertTrue(rows(applied.gaps).isEmpty())
            assertEquals(
                listOf(SpoolRecoveryEvent("partial_segment", segment.wireKeys.endEpochMs, "final already exists")),
                applied.events,
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

            val applied = run(baseDir)

            assertTrue(Files.exists(draftDir), "draft should be kept on payload byte mismatch")
            assertEquals("divergent final", applied.events.single().detail)
            assertEquals(1, applied.unknown.size)
            assertTrue(rows(applied.gaps).isEmpty())
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

            val applied = run(baseDir)

            assertFalse(Files.exists(draftDir), "draft should be deleted on payload byte match")
            assertTrue(rows(applied.gaps).isEmpty())
            assertEquals(
                listOf(SpoolRecoveryEvent("partial_segment", segment.wireKeys.endEpochMs, "final already exists")),
                applied.events,
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

            val applied = run(baseDir)

            assertTrue(Files.exists(bareFinalDir.resolve("manifest")))
            assertTrue(Files.exists(suffixedFinalDir.resolve("manifest")))
            assertFalse(Files.exists(draftDir))
            assertEquals(listOf("finalized_segment"), applied.events.map { it.kind })
            assertEquals(suffixedLeaf, applied.events.single().detail)
            assertEquals("delivered", rows(applied.gaps).single().reason)
            val failing = GapNoticeStore(baseDir.resolve("gap-fail"), syncDirectory = { throw java.io.IOException("sync") })
            val again = Files.createTempDirectory("recovery-suffixed-move")
            try {
                val movedDraft = again.resolve(".draft").resolve(segment.key.day).resolve(segment.stream).resolve(suffixedLeaf)
                val movedFinal = again.resolve(segment.key.day).resolve(segment.stream).resolve(suffixedLeaf)
                Files.createDirectories(movedDraft)
                Files.write(movedDraft.resolve("manifest"), Files.readAllBytes(suffixedFinalDir.resolve("manifest")))
                applyRecoveryActions(RecoveryScanner(again).scan(nowEpochMs = 10L), failing, mutableListOf(), again)
                assertTrue(Files.isRegularFile(movedFinal.resolve("manifest")))
            } finally {
                again.deleteRecursively()
            }
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

            val applied = run(baseDir)

            assertFalse(Files.exists(draftDir))
            assertEquals(listOf(SpoolRecoveryEvent("partial_segment", 10L, "missing manifest")), applied.events)
            val recorded = rows(applied.gaps)
            assertEquals(listOf("empty draft"), recorded.map { it.reason })
            assertTrue(recorded.none { it.kind == "loss" })
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun anUnreadableInvalidPayloadManifestLeavesTheDraft() {
        val baseDir = Files.createTempDirectory("recovery-unreadable-payload")
        try {
            val draftDir = baseDir.resolve(".draft").resolve("20260304").resolve(MAIN_STREAM).resolve("bad")
            Files.createDirectories(draftDir)
            Files.write(draftDir.resolve("manifest"), "not a manifest".toByteArray(StandardCharsets.UTF_8))
            Files.write(draftDir.resolve("audio.m4a"), "payload".toByteArray(StandardCharsets.UTF_8))
            val gaps = GapNoticeStore(baseDir.resolve("gap"))
            val events = applyRecoveryActions(
                listOf(
                    RecoveryAction.Discard(
                        draftDir = draftDir,
                        event = SpoolRecoveryEvent("partial_segment", 10L, "invalid payload audio.m4a"),
                    ),
                ),
                gaps,
                mutableListOf(),
                baseDir,
            )
            assertTrue(Files.isRegularFile(draftDir.resolve("manifest")))
            assertTrue(Files.isRegularFile(draftDir.resolve("audio.m4a")))
            assertTrue(rows(gaps).none { it.kind == "loss" || it.reason == "no bytes" })
            assertTrue(events.isEmpty())
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun anEmptyFileListOnAnInvalidPayloadLeavesTheDraft() {
        val baseDir = Files.createTempDirectory("recovery-empty-file-list")
        try {
            val segment = emptySegment()
            val draftDir = baseDir.resolve(".draft").resolve(segment.key.day).resolve(segment.stream).resolve("bad")
            Files.createDirectories(draftDir)
            Files.write(
                draftDir.resolve("manifest"),
                serializeManifest(segment, app.solstone.core.model.BundleManifest(segment.key, emptyList(), emptyList()))
                    .toByteArray(StandardCharsets.UTF_8),
            )
            Files.write(draftDir.resolve("audio.m4a"), "payload".toByteArray(StandardCharsets.UTF_8))
            val gaps = GapNoticeStore(baseDir.resolve("gap"))
            val events = applyRecoveryActions(
                listOf(
                    RecoveryAction.Discard(
                        draftDir = draftDir,
                        event = SpoolRecoveryEvent("partial_segment", 10L, "invalid payload audio.m4a"),
                    ),
                ),
                gaps,
                mutableListOf(),
                baseDir,
            )
            assertTrue(Files.isRegularFile(draftDir.resolve("manifest")))
            assertTrue(Files.isRegularFile(draftDir.resolve("audio.m4a")))
            assertTrue(rows(gaps).none { it.kind == "loss" || it.reason == "no bytes" })
            assertTrue(events.isEmpty())
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun droppedRecoveryEventsLogTheKeptDraftAndSkipPathsAndInvalidPayloads() {
        val logged = mutableListOf<List<String>>()
        val emit = { kind: String, source: String, reason: String, material: String ->
            logged += listOf(kind, source, reason, material)
        }
        emitDroppedRecoveryEvent(SpoolRecoveryEvent("partial_segment", 1L, "kept unknown"), emit)
        emitDroppedRecoveryEvent(SpoolRecoveryEvent("partial_segment", 1L, "divergent final"), emit)
        emitDroppedRecoveryEvent(SpoolRecoveryEvent("partial_segment", 1L, "final already exists"), emit)
        emitDroppedRecoveryEvent(SpoolRecoveryEvent("partial_segment", 1L, "day/stream/leaf"), emit)
        emitDroppedRecoveryEvent(SpoolRecoveryEvent("finalized_segment", 1L, "000000_1"), emit)
        emitDroppedRecoveryEvent(SpoolRecoveryEvent("partial_segment", 1L, "invalid payload audio.m4a"), emit)
        emitDroppedRecoveryEvent(SpoolRecoveryEvent("partial_segment", 1L, null), emit)
        assertEquals(
            listOf(
                listOf("diagnostic", "spool", "kept unknown", "draft"),
                listOf("diagnostic", "spool", "divergent final", "draft"),
                listOf("diagnostic", "spool", "final already exists", "draft"),
            ),
            logged,
        )
    }

    @Test
    fun aFailedEmptyDraftDiagnosticStillDeletesTheDraft() {
        val baseDir = Files.createTempDirectory("recovery-empty-fail")
        try {
            val draftDir = baseDir.resolve(".draft").resolve("20260304").resolve(MAIN_STREAM).resolve("bad")
            Files.createDirectories(draftDir)
            val gaps = GapNoticeStore(baseDir.resolve("gap"), syncDirectory = { throw java.io.IOException("sync") })
            val events = applyRecoveryActions(RecoveryScanner(baseDir).scan(10L), gaps, mutableListOf(), baseDir)
            assertFalse(Files.exists(draftDir))
            assertEquals("missing manifest", events.single().detail)
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun oneDroppedRecoveryEventThrowDoesNotSkipTheNext() {
        val logged = mutableListOf<String>()
        emitDroppedRecoveryEvents(
            listOf(
                SpoolRecoveryEvent("partial_segment", 1L, "kept unknown"),
                SpoolRecoveryEvent("partial_segment", 2L, "divergent final"),
            ),
        ) { _, _, reason, _ ->
            if (reason == "kept unknown") throw IllegalStateException("listener")
            logged += reason
        }
        assertEquals(listOf("divergent final"), logged)
    }

    private class Applied(
        val events: List<SpoolRecoveryEvent>,
        val unknown: List<String>,
        val gaps: GapNoticeStore,
    )

    private fun run(baseDir: Path, gaps: GapNoticeStore = GapNoticeStore(baseDir.resolve("gap"))): Applied {
        val unknown = mutableListOf<String>()
        val events = applyRecoveryActions(RecoveryScanner(baseDir).scan(nowEpochMs = 10L), gaps, unknown, baseDir)
        return Applied(events, unknown, gaps)
    }

    private fun rows(gaps: GapNoticeStore): List<GapNoticeRow> =
        assertIs<GapRead.Ready>(gaps.read(ZoneId.of("UTC"))).rows

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
