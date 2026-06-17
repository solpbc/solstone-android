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

            val events = applyRecoveryActions(RecoveryScanner(baseDir).scan(nowEpochMs = 10L))
            val finalDir = baseDir.resolve(segment.key.day).resolve(segment.stream).resolve(segment.key.segment)

            assertTrue(Files.isRegularFile(finalDir.resolve("manifest")))
            assertFalse(Files.exists(draftDir))
            assertEquals(listOf("finalized_segment"), events.map { it.kind })
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

            val events = applyRecoveryActions(RecoveryScanner(baseDir).scan(nowEpochMs = 10L))

            assertEquals(sentinel, String(Files.readAllBytes(finalDir.resolve("manifest")), StandardCharsets.UTF_8))
            assertFalse(Files.exists(draftDir))
            assertEquals(
                listOf(SpoolRecoveryEvent("partial_segment", segment.wireKeys.endEpochMs, "final already exists")),
                events,
            )
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

            val events = applyRecoveryActions(RecoveryScanner(baseDir).scan(nowEpochMs = 10L))

            assertFalse(Files.exists(draftDir))
            assertEquals(listOf(SpoolRecoveryEvent("partial_segment", 10L, "missing manifest")), events)
        } finally {
            baseDir.deleteRecursively()
        }
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
