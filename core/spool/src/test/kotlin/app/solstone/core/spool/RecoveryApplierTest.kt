// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.segment.sha256
import app.solstone.core.segment.wireKeys
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecoveryApplierTest {
    @Test
    fun onlyACompleteVerifiedTwinRetiresDraftResidue() {
        val cases = listOf("missing", "malformed", "valid", "valid-extra", "valid-extra-no-twin", "invalid-extra", "invalid-extra-no-twin", "extra", "nested", "symlink", "different", "absent", "bad-final", "corrupt-final")
        for (case in cases) {
            val root = Files.createTempDirectory("residue-$case")
            try {
                val segment = emptySegment()
                val relative = Path.of(segment.key.day, segment.stream, segment.key.segment)
                val draft = root.resolve(".draft").resolve(relative)
                val sealed = root.resolve(relative)
                Files.createDirectories(draft)
                Files.createDirectories(sealed)
                val bytes = "owner audio".toByteArray()
                Files.write(draft.resolve("audio.m4a"), bytes)
                Files.write(sealed.resolve("audio.m4a"), bytes)
                val file = BundleFile("audio", "audio.m4a", sha256(draft.resolve("audio.m4a")), bytes.size.toLong(), "audio/mp4", BASE_EPOCH_MS, BASE_EPOCH_MS + 1000)
                val manifest = serializeManifest(segment, BundleManifest(segment.key, listOf(file), emptyList())).toByteArray()
                Files.write(sealed.resolve("manifest"), manifest)
                when (case) {
                    "malformed" -> Files.write(draft.resolve("manifest"), byteArrayOf(1, 2, 3))
                    "valid", "valid-extra", "valid-extra-no-twin", "invalid-extra", "invalid-extra-no-twin" -> Files.write(draft.resolve("manifest"), manifest)
                }
                when (case) {
                    "extra", "valid-extra", "valid-extra-no-twin", "invalid-extra", "invalid-extra-no-twin" -> Files.write(draft.resolve("unlisted.m4a"), bytes)
                    "nested" -> Files.createDirectory(draft.resolve("nested"))
                    "symlink" -> {
                        Files.delete(draft.resolve("audio.m4a"))
                        Files.createSymbolicLink(draft.resolve("audio.m4a"), sealed.resolve("audio.m4a"))
                    }
                    "different" -> Files.write(draft.resolve("audio.m4a"), "other audio".toByteArray())
                    "absent" -> sealed.deleteRecursively()
                    "bad-final" -> Files.write(sealed.resolve("manifest"), byteArrayOf(1))
                    "corrupt-final" -> Files.write(sealed.resolve("audio.m4a"), "other audio".toByteArray())
                }
                if (case.startsWith("invalid-extra")) Files.write(draft.resolve("audio.m4a"), "bad".toByteArray())
                if (case.endsWith("no-twin")) sealed.deleteRecursively()
                val expected = Files.readAllBytes(draft.resolve("audio.m4a"))
                val retired = case in setOf("missing", "malformed", "valid")
                val applied = run(root)
                assertEquals(!retired, Files.exists(draft), case)
                assertEquals(if (retired) 0 else 1, applied.unknown.size, case)
                assertTrue(rows(applied.gaps).isEmpty(), case)
                if (!retired) {
                    assertContentEquals(expected, Files.readAllBytes(draft.resolve("audio.m4a")), case)
                    if ("extra" in case) assertContentEquals(bytes, Files.readAllBytes(draft.resolve("unlisted.m4a")), case)
                    assertEquals(1, run(root, applied.gaps).unknown.size, case)
                }
            } finally {
                root.deleteRecursively()
            }
        }
    }

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

    @Test
    fun aParkedDraftWithAValidManifestIsNeverFinalizedOrQueued() {
        val baseDir = Files.createTempDirectory("recovery-parked")
        try {
            val day = "20261101"
            val stream = "observer"
            val leaf = "011500_300"
            val start = 1_793_515_500_000L
            val draft = baseDir.resolve(".draft").resolve(day).resolve(stream).resolve(leaf)
            Files.createDirectories(draft)
            val oldBytes = "old-audio".toByteArray(StandardCharsets.UTF_8)
            val payload = draft.resolve("other.bin")
            Files.write(payload, oldBytes)
            val parkedSegment = sealSegment(day, stream, leaf, start, "other.bin", oldBytes.size.toLong())
            val file = BundleFile(
                sourceId = "audio",
                name = "other.bin",
                sha256 = sha256(payload),
                byteSize = oldBytes.size.toLong(),
                mediaType = "application/octet-stream",
                captureStartEpochMs = start,
                captureEndEpochMs = start + 300_000L,
            )
            Files.write(
                draft.resolve("manifest"),
                serializeManifest(parkedSegment, BundleManifest(parkedSegment.key, listOf(file), emptyList()))
                    .toByteArray(StandardCharsets.UTF_8),
            )
            val failing = GapNoticeStore(baseDir.resolve("gap"), beforeWrite = { path ->
                if (path.fileName.toString() == "events.v1") throw java.io.IOException("events")
            })
            FileSpoolWriter(baseDir, gaps = failing).seal(
                sealSegment(day, stream, leaf, start, "first.bin", "new-bytes".length.toLong()),
                bytesProvider("first.bin" to "new-bytes".toByteArray(StandardCharsets.UTF_8)),
            )

            val parked = baseDir.resolve(".parked").resolve(day).resolve(stream).resolve("$leaf.kept-1")
            val sealed = baseDir.resolve(day).resolve(stream).resolve(leaf)
            val queuedKept = baseDir.resolve(day).resolve(stream).resolve("$leaf.kept-1")
            assertTrue(Files.isRegularFile(parked.resolve("manifest")))
            assertTrue(Files.isRegularFile(parked.resolve("other.bin")))
            assertFalse(Files.exists(baseDir.resolve(".draft").resolve(day).resolve(stream).resolve("$leaf.kept-1")))
            assertContentEquals("new-bytes".toByteArray(StandardCharsets.UTF_8), Files.readAllBytes(sealed.resolve("first.bin")))
            assertEquals(leaf, manifestSegment(parked))
            assertEquals(manifestSegment(sealed), manifestSegment(parked))

            val before = RecoveryScanner(baseDir).scan(nowEpochMs = 10L)
            assertTrue(before.none { it is RecoveryAction.Finalize })
            assertEquals(parked, (before.single() as RecoveryAction.Parked).draftDir)

            val unknown = mutableListOf<String>()
            val events = applyRecoveryActions(before, failing, unknown, baseDir)
            assertTrue(events.none { it.kind == "finalized_segment" })
            assertEquals(listOf("kept unknown"), events.map { it.detail })
            assertTrue(unknown.any { ".parked" in it && ".kept-" in it })
            assertTrue(Files.isRegularFile(parked.resolve("manifest")))
            assertFalse(Files.exists(queuedKept))
            assertEquals(listOf(leaf), queueableManifests(baseDir).map { it.parent.fileName.toString() })

            val working = GapNoticeStore(baseDir.resolve("gap-working"))
            val laterUnknown = mutableListOf<String>()
            val later = applyRecoveryActions(
                RecoveryScanner(baseDir).scan(nowEpochMs = 11L),
                working,
                laterUnknown,
                baseDir,
            )
            assertTrue(later.none { it.kind == "finalized_segment" })
            assertTrue(laterUnknown.isEmpty())
            assertFalse(Files.exists(parked))
            assertFalse(Files.exists(queuedKept))
            assertEquals(listOf(leaf), queueableManifests(baseDir).map { it.parent.fileName.toString() })
            val loss = rows(working).single { it.kind == "loss" }
            assertEquals("spool", loss.source)
            assertEquals("undeliverable", loss.reason)
            assertEquals(gapDraftMaterial(day, stream, leaf), loss.material)
            assertContentEquals("new-bytes".toByteArray(StandardCharsets.UTF_8), Files.readAllBytes(sealed.resolve("first.bin")))
        } finally {
            baseDir.deleteRecursively()
        }
    }

    private fun sealSegment(
        day: String,
        stream: String,
        leaf: String,
        start: Long,
        payloadName: String,
        byteSize: Long,
    ): SealedSegment = SealedSegment(
        stream = stream,
        key = SegmentKey(day, leaf),
        wireKeys = WireKeys(
            day = day,
            segment = leaf,
            startEpochMs = start,
            endEpochMs = start + 300_000L,
            zoneId = "America/New_York",
            utcOffsetSeconds = -14_400,
        ),
        payloads = listOf(
            SegmentPayload(
                sourceId = "audio",
                ref = PayloadRef(payloadName, "application/octet-stream", byteSize, null),
                captureStartEpochMs = start,
                captureEndEpochMs = start + 300_000L,
            ),
        ),
        gaps = emptyList(),
    )

    private fun bytesProvider(vararg entries: Pair<String, ByteArray>): PayloadBytesProvider {
        val bytesByName = entries.toMap()
        return object : PayloadBytesProvider {
            override fun open(payload: SegmentPayload): ByteArrayInputStream =
                ByteArrayInputStream(bytesByName.getValue(payload.ref.name))
        }
    }

    private fun manifestSegment(dir: Path): String =
        parseManifest(String(Files.readAllBytes(dir.resolve("manifest")), StandardCharsets.UTF_8)).manifest.key.segment

    private fun queueableManifests(baseDir: Path): List<Path> {
        if (!Files.exists(baseDir)) return emptyList()
        return Files.walk(baseDir).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "manifest" }
                .filter { manifest ->
                    val parts = baseDir.relativize(manifest).toString().replace('\\', '/').split('/')
                    if (parts.any { it == ".draft" || it == ".parked" }) return@filter false
                    val dayDir = manifest.parent?.parent?.parent ?: return@filter false
                    dayDir.parent == baseDir
                }
                .toList()
        }
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
