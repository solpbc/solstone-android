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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryEvidenceTest {
    @Test
    fun invalidCameraInMixedDraftStillEstablishesDiscardedAudio() = withDraft { root, draft ->
        writeManifest(draft, listOf("audio", "camera"))
        val audio = Marker()
        val other = Marker()
        val unknown = Marker()
        applyRecoveryActions(RecoveryScanner(root).scan(), audio, other, unknown)
        assertEquals(1, audio.marks)
        assertEquals(0, other.marks)
        assertEquals(0, unknown.marks)
        assertFalse(Files.exists(draft))
    }

    @Test
    fun knownNonAudioDiscardCannotSetAudioDiagnosis() = withDraft { root, draft ->
        writeManifest(draft, listOf("camera"))
        val audio = Marker()
        val other = Marker()
        val unknown = Marker()
        applyRecoveryActions(RecoveryScanner(root).scan(), audio, other, unknown)
        assertEquals(0, audio.marks)
        assertEquals(1, other.marks)
        assertEquals(0, unknown.marks)
    }

    @Test
    fun unknownContentIsPreservedWithoutAnInventedAudioDiagnosis() = withDraft { root, draft ->
        Files.write(draft.resolve("unknown.bin"), "keep these bytes".toByteArray())
        val audio = Marker()
        val other = Marker()
        val unknown = Marker()
        applyRecoveryActions(RecoveryScanner(root).scan(), audio, other, unknown)
        assertEquals(0, audio.marks)
        assertEquals(0, other.marks)
        assertEquals(1, unknown.marks)
        assertTrue(Files.exists(draft.resolve("unknown.bin")))
    }

    @Test
    fun failedDurableNoticePreservesKnownAudioDraft() = withDraft { root, draft ->
        writeManifest(draft, listOf("audio", "camera"))
        val failedStore = UnresolvedInterruptionStore(root.resolve("audio-notice")) {
            throw java.io.IOException("directory publication failed")
        }
        applyRecoveryActions(RecoveryScanner(root).scan(), failedStore, Marker(), Marker())
        assertTrue(Files.exists(draft.resolve("audio.bin")))
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

    private class Marker : UnresolvedInterruption {
        var marks = 0
        override fun markUnresolved(): Boolean { marks++; return true }
        override fun isUnresolved(): Boolean = marks > 0
    }
}
