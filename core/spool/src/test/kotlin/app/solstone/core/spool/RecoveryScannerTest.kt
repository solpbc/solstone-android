// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.sha256
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecoveryScannerTest {
    @Test
    fun completeDraftFinalizes() {
        withTempDir { baseDir ->
            val draftDir = writeDraft(baseDir, payload = "complete bytes")

            val actions = RecoveryScanner(baseDir).scan(nowEpochMs = 5)

            assertEquals(1, actions.size)
            val finalize = actions.single() as RecoveryAction.Finalize
            assertEquals(draftDir, finalize.draftDir)
            assertEquals(baseDir.resolve("20260616/camera/120000_300"), finalize.finalDir)
            assertEquals("payload.bin", finalize.parsedManifest.manifest.files.single().name)
        }
    }

    @Test
    fun missingManifestDiscardsPartialSegment() {
        withTempDir { baseDir ->
            val draftDir = baseDir.resolve(".draft/20260616/camera/120000_300")
            Files.createDirectories(draftDir)

            val actions = RecoveryScanner(baseDir).scan(nowEpochMs = 7)

            assertEquals(1, actions.size)
            val discard = actions.single() as RecoveryAction.Discard
            assertEquals(draftDir, discard.draftDir)
            assertEquals("partial_segment", discard.event.kind)
            assertEquals(7, discard.event.atEpochMs)
        }
    }

    @Test
    fun missingOrShortPayloadDiscardsPartialSegment() {
        withTempDir { baseDir ->
            val draftDir = writeDraft(baseDir, payload = "complete bytes")
            Files.writeString(draftDir.resolve("payload.bin"), "short", StandardCharsets.UTF_8)

            val actions = RecoveryScanner(baseDir).scan(nowEpochMs = 9)

            assertEquals(1, actions.size)
            val discard = actions.single() as RecoveryAction.Discard
            assertEquals(draftDir, discard.draftDir)
            assertEquals("partial_segment", discard.event.kind)
            assertTrue(discard.event.detail?.contains("payload.bin") == true)
        }
    }

    @Test
    fun missingPayloadDiscardsPartialSegment() {
        withTempDir { baseDir ->
            val draftDir = writeDraft(baseDir, payload = "complete bytes")
            Files.delete(draftDir.resolve("payload.bin"))

            val actions = RecoveryScanner(baseDir).scan(nowEpochMs = 10)

            assertEquals(1, actions.size)
            val discard = actions.single() as RecoveryAction.Discard
            assertEquals(draftDir, discard.draftDir)
            assertEquals("partial_segment", discard.event.kind)
            assertTrue(discard.event.detail?.contains("payload.bin") == true)
        }
    }

    @Test
    fun cleanTreeHasNoRecoveryActions() {
        withTempDir { baseDir ->
            assertTrue(RecoveryScanner(baseDir).scan().isEmpty())
        }
    }

    private fun writeDraft(baseDir: Path, payload: String): Path {
        val draftDir = baseDir.resolve(".draft/20260616/camera/120000_300")
        Files.createDirectories(draftDir)
        val payloadPath = draftDir.resolve("payload.bin")
        Files.writeString(payloadPath, payload, StandardCharsets.UTF_8)
        val file = BundleFile(
            sourceId = "camera",
            name = "payload.bin",
            sha256 = sha256(payloadPath),
            byteSize = Files.size(payloadPath),
            mediaType = "application/octet-stream",
            captureStartEpochMs = 1_800,
            captureEndEpochMs = 2_100,
        )
        val segment = SealedSegment(
            stream = "camera",
            key = SegmentKey("20260616", "120000_300"),
            wireKeys = WireKeys(
                day = "20260616",
                segment = "120000_300",
                startEpochMs = 1_800,
                endEpochMs = 2_100,
                zoneId = "America/Denver",
                utcOffsetSeconds = -21_600,
            ),
            payloads = emptyList(),
            gaps = emptyList(),
        )
        val manifest = BundleManifest(segment.key, files = listOf(file), gaps = emptyList())
        Files.writeString(draftDir.resolve("manifest"), serializeManifest(segment, manifest), StandardCharsets.UTF_8)
        return draftDir
    }

    private fun withTempDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("solstone-recovery-scanner")
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun Path.deleteRecursively() {
        Files.walk(this).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
