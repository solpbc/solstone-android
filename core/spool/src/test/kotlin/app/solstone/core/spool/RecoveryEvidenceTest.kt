// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.sha256
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecoveryEvidenceTest {
    @Test
    fun invalidCameraInMixedDraftStillEstablishesDiscardedAudio() = withDraft { root, draft ->
        writeManifest(draft, listOf("audio", "camera"))
        val gaps = GapNoticeStore(root.resolve("gap"))
        val unknown = mutableListOf<String>()
        applyRecoveryActions(RecoveryScanner(root).scan(), gaps, unknown, root)
        val losses = rows(gaps).filter { it.kind == "loss" }
        assertEquals(setOf("audio", "camera"), losses.map { it.source }.toSet())
        assertTrue(losses.all { it.reason == "invalid payload" && it.material == draft.fileName.toString() })
        assertFalse(Files.exists(draft))
        assertTrue(unknown.isEmpty())
    }

    @Test
    fun knownNonAudioDiscardCannotSetAudioDiagnosis() = withDraft { root, draft ->
        writeManifest(draft, listOf("camera"))
        val gaps = GapNoticeStore(root.resolve("gap"))
        applyRecoveryActions(RecoveryScanner(root).scan(), gaps, mutableListOf(), root)
        val losses = rows(gaps).filter { it.kind == "loss" }
        assertEquals(listOf("camera"), losses.map { it.source })
        assertTrue(losses.none { it.source == "audio" })
        assertFalse(Files.exists(draft))
    }

    @Test
    fun unknownContentIsPreservedWithoutAnInventedAudioDiagnosis() = withDraft { root, draft ->
        Files.write(draft.resolve("unknown.bin"), "keep these bytes".toByteArray())
        val gaps = GapNoticeStore(root.resolve("gap"))
        val unknown = mutableListOf<String>()
        val events = applyRecoveryActions(RecoveryScanner(root).scan(), gaps, unknown, root)
        assertTrue(rows(gaps).isEmpty())
        assertTrue(events.isEmpty())
        assertEquals(1, unknown.size)
        assertTrue(Files.exists(draft.resolve("unknown.bin")))
    }

    @Test
    fun failedDurableNoticePreservesKnownAudioDraft() = withDraft { root, draft ->
        writeManifest(draft, listOf("audio", "camera"))
        val failedStore = GapNoticeStore(root.resolve("gap"), syncDirectory = { throw java.io.IOException("directory publication failed") })
        applyRecoveryActions(RecoveryScanner(root).scan(), failedStore, mutableListOf(), root)
        assertTrue(Files.exists(draft.resolve("audio.bin")))
        assertTrue(Files.exists(draft.resolve("camera.bin")))
    }

    @Test
    fun invalidPayloadWithAShortSiblingRecordsOneLossPerSourceThatHadBytes() = withDraft { root, draft ->
        writeManifest(draft, listOf("audio", "camera"))
        Files.write(draft.resolve("audio.bin"), ByteArray(0))
        val gaps = GapNoticeStore(root.resolve("gap"))
        applyRecoveryActions(RecoveryScanner(root).scan(), gaps, mutableListOf(), root)
        val recorded = rows(gaps)
        assertEquals(listOf("camera"), recorded.filter { it.kind == "loss" }.map { it.source })
        assertTrue(recorded.any { it.kind == "diagnostic" && it.source == "audio" && it.reason == "no bytes" })
        assertFalse(Files.exists(draft))
    }

    @Test
    fun malformedManifestIsKeptWithNoGapRow() = withDraft { root, draft ->
        Files.write(draft.resolve("manifest"), "not a manifest".toByteArray())
        val gaps = GapNoticeStore(root.resolve("gap"))
        val unknown = mutableListOf<String>()
        val events = applyRecoveryActions(RecoveryScanner(root).scan(), gaps, unknown, root)
        assertTrue(events.isEmpty())
        assertTrue(rows(gaps).isEmpty())
        assertEquals(listOf(".draft/20261003/main/000000_1"), unknown)
        assertTrue(Files.exists(draft.resolve("manifest")))
    }

    private fun writeManifest(draft: Path, sources: List<String>) {
        val keys = WireKeys("20261003", "000000_1", 1000, 2000, "UTC", 0)
        val files = sources.map { source ->
            val name = "$source.bin"
            val payload = draft.resolve(name)
            Files.write(payload, "$source bytes".toByteArray())
            BundleFile(source, name, if (source == "camera") "invalid" else sha256(payload),
                Files.size(payload), if (source == "audio") "audio/mp4" else "application/octet-stream", 1000, 2000)
        }
        val segment = SealedSegment("main", SegmentKey(keys.day, keys.segment), keys, emptyList(), emptyList())
        Files.write(draft.resolve("manifest"), serializeManifest(segment, BundleManifest(segment.key, files, emptyList())).toByteArray())
    }

    private fun withDraft(block: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory("typed-recovery")
        try {
            val draft = root.resolve(".draft/20261003/main/000000_1")
            Files.createDirectories(draft)
            block(root, draft)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun rows(gaps: GapNoticeStore): List<GapNoticeRow> =
        assertIs<GapRead.Ready>(gaps.read(ZoneId.of("UTC"))).rows
}
