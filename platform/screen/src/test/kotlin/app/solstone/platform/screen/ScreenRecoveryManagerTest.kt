// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.sha256
import app.solstone.core.spool.SealResult
import app.solstone.core.spool.SealedSegmentSink
import app.solstone.core.spool.GapNoticeStore
import app.solstone.core.spool.GapRead
import app.solstone.core.spool.gapLossFactExists
import app.solstone.core.spool.screenIndexHasRealSample
import app.solstone.platform.audio.AudioCommitStatus
import app.solstone.platform.audio.AudioSpoolFact
import app.solstone.platform.audio.AudioSpoolLookup
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScreenRecoveryManagerTest {

    private fun gapsOf(root: File) = GapNoticeStore(File(root, "gap").toPath())

    private fun rows(store: GapNoticeStore) =
        assertIs<GapRead.Ready>(store.read(java.time.ZoneId.of("UTC"))).rows

    private class RecordingSealedSink : SealedSegmentSink {
        val persisted = mutableListOf<Pair<SealedSegment, SealResult>>()
        override fun persistSealed(segment: SealedSegment, result: SealResult, sealedAtEpochMs: Long) {
            persisted += (segment to result)
        }
    }

    private class FakeRemuxer(
        var succeed: Boolean = true,
        var sampleDurationMs: Long = 10_000L,
        var content: String = "fake mp4 content",
    ) : ScreenRemuxer {
        override fun remux(attemptDir: File, h264File: File, indexFile: File, recordFile: File, mp4File: File): ScreenRemuxResult {
            if (!succeed) {
                return ScreenRemuxResult.Failure("simulated remux failure")
            }
            mp4File.writeText(content)
            return ScreenRemuxResult.Success(sampleDurationMs = sampleDurationMs, outputBytes = mp4File.length())
        }
    }

    private fun createAttemptDir(
        sourceDir: File,
        attemptId: String,
        windowStart: Long,
        captureStart: Long,
        captureEnd: Long,
        mp4Content: String? = null,
    ): File {
        val dir = File(sourceDir, attemptId).apply { mkdirs() }
        File(dir, "capture.h264").writeBytes(ByteArray(100))
        File(dir, "samples.idx").writeBytes(ByteArray(20))
        if (mp4Content != null) {
            File(dir, ScreenConstants.PAYLOAD_NAME).writeText(mp4Content)
        }
        val info = ScreenRecordInfo(
            id = attemptId,
            captureStartEpochMs = captureStart,
            zoneId = "UTC",
            utcOffsetSeconds = 0,
            windowStartEpochMs = windowStart,
            windowEndEpochMs = windowStart + 300_000L,
            width = 720,
            height = 1280,
            openEndEpochMs = captureEnd,
            firstFrameEpochMs = captureStart,
            sampleDurationMs = if (mp4Content != null) (captureEnd - captureStart) else null,
        )
        File(dir, "record.txt").writeText(info.serialize())
        return dir
    }

    @Test
    fun roomFailureRetainsAttemptAndRetryUsesFirstFrameKeyWithoutAnotherDirectory() {
        val root = Files.createTempDirectory("screen-ledger-retry").toFile()
        try {
            val source = File(root, "screen-source").apply { mkdirs() }
            val spool = File(root, "spool").toPath()
            val grid = 1_800_000_000_000L
            val dir = createAttemptDir(source, "rec-retry", grid, grid + 89_000L, grid + 100_000L)
            val record = File(dir, "record.txt")
            val info = ScreenRecordInfo.parse(record.readText())!!
            record.writeText(info.copy(firstFrameEpochMs = grid + 90_000L).serialize())
            var fail = true
            val adopted = mutableListOf<SealedSegment>()
            val manager = ScreenRecoveryManager(source, spool, { _, _ -> emptySet() },
                AudioSpoolLookup { _, _, _, _, _, _, _, _ -> AudioCommitStatus.NotFound },
                { segment, result, _ ->
                    assertEquals(segment.key, result.manifest.key)
                    if (fail) error("Room unavailable")
                    adopted += segment
                }, gapsOf(root), FakeRemuxer(sampleDurationMs = 10_000L))
            manager.recover()
            assertTrue(dir.exists())
            assertTrue(File(dir, "capture.h264").exists())
            fail = false
            manager.recover()
            assertFalse(dir.exists())
            assertEquals(1, adopted.size)
            assertEquals(grid + 90_000L, adopted.single().wireKeys.startEpochMs)
            assertEquals(grid + 100_000L, adopted.single().wireKeys.endEpochMs)
            assertEquals(app.solstone.core.segment.wireKeys(grid + 90_000L, grid + 100_000L,
                java.time.ZoneId.of("UTC")).segment, adopted.single().key.segment)
            val manifests = Files.walk(spool).use { paths -> paths.filter { it.fileName.toString() == "manifest" }.count() }
            assertEquals(1L, manifests)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun adoptViaFileSpoolWriterAndSecondPassCommittedMatch() {
        val baseDir = Files.createTempDirectory(Path.of("/var/tmp"), "screen-recovery-test-").toFile()
        try {
            val sourceDir = File(baseDir, "screen-source").apply { mkdirs() }
            val spoolDir = File(baseDir, "spool").toPath().apply { Files.createDirectories(this) }
            val occupied = mutableSetOf<String>()
            val gaps = gapsOf(baseDir)
            val sink = RecordingSealedSink()
            val facts = mutableListOf<AudioSpoolFact>()

            val windowStart = 1_700_000_000_000L
            val captureStart = windowStart + 10_000L
            val captureEnd = windowStart + 20_000L

            val attemptDir = createAttemptDir(sourceDir, "rec-pass1", windowStart, captureStart, captureEnd)

            val manager = ScreenRecoveryManager(
                screenSourceDir = sourceDir,
                spoolDir = spoolDir,
                occupiedLeaves = { _, _ -> occupied },
                lookup = AudioSpoolLookup { _, _, _, _, _, _, _, _ -> AudioCommitStatus.NotFound },
                sealedSink = { segment, result, epoch ->
                    sink.persistSealed(segment, result, epoch)
                    val leaf = result.directory?.fileName?.toString() ?: segment.key.segment
                    occupied += leaf
                    val mp4File = spoolDir.resolve(segment.key.day).resolve("observer").resolve(leaf).resolve("screen.mp4").toFile()
                    val sha = if (mp4File.exists()) sha256(mp4File.toPath()) else "dummy"
                    facts += AudioSpoolFact(
                        segmentId = "${segment.key.day}/observer/$leaf",
                        sha256 = sha,
                        captureStartEpochMs = captureStart,
                        captureEndEpochMs = captureEnd,
                        sourceId = ScreenConstants.SOURCE_ID,
                        name = ScreenConstants.PAYLOAD_NAME,
                        spoolPayloadExists = true,
                        spoolPayloadShaMatches = true,
                        spoolCustodyDurable = true,
                    )
                },
                gaps = gaps,
                remuxer = FakeRemuxer(succeed = true, sampleDurationMs = 10_000L, content = "portion-1-bytes"),
                factsProvider = { _, _, _, _ -> facts },
            )

            // 1. First pass: adopts via FileSpoolWriter and deletes directory
            manager.recover()
            assertFalse(attemptDir.exists(), "Attempt dir should be deleted after successful adopt")
            assertEquals(1, sink.persisted.size)

            // 2. Second pass with CommittedMatch: if the attempt exists and matches already committed facts
            val attemptDir2 = createAttemptDir(sourceDir, "rec-pass2", windowStart, captureStart, captureEnd, mp4Content = "portion-1-bytes")
            assertTrue(attemptDir2.exists())
            manager.recover()
            assertFalse(attemptDir2.exists(), "Attempt dir should be deleted on CommittedMatch without resealing")
            assertEquals(1, sink.persisted.size, "Should not reseal already committed match")
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun failedRemuxKeepsDirectory() {
        val baseDir = Files.createTempDirectory(Path.of("/var/tmp"), "screen-recovery-fail-").toFile()
        try {
            val sourceDir = File(baseDir, "screen-source").apply { mkdirs() }
            val spoolDir = File(baseDir, "spool").toPath().apply { Files.createDirectories(this) }
            val gaps = gapsOf(baseDir)
            val sink = RecordingSealedSink()

            val attemptDir = createAttemptDir(sourceDir, "rec-fail", 1_700_000_000_000L, 1_700_000_010_000L, 1_700_000_020_000L)

            val manager = ScreenRecoveryManager(
                screenSourceDir = sourceDir,
                spoolDir = spoolDir,
                occupiedLeaves = { _, _ -> emptySet() },
                lookup = AudioSpoolLookup { _, _, _, _, _, _, _, _ -> AudioCommitStatus.NotFound },
                sealedSink = sink,
                gaps = gaps,
                remuxer = FakeRemuxer(succeed = false),
            )

            manager.recover()

            assertTrue(attemptDir.exists(), "Failed remux must keep the directory")
            assertEquals(listOf("no captured frame"), rows(gaps).map { it.reason })
            assertTrue(rows(gaps).none { it.kind == "loss" })
            assertEquals(0, sink.persisted.size)
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun twoPortionsInOneWindowKeepTheirDistinctFirstFrameKeys() {
        val baseDir = Files.createTempDirectory(Path.of("/var/tmp"), "screen-recovery-ws-").toFile()
        try {
            val sourceDir = File(baseDir, "screen-source").apply { mkdirs() }
            val spoolDir = File(baseDir, "spool").toPath().apply { Files.createDirectories(this) }
            val occupied = mutableSetOf<String>()
            val gaps = gapsOf(baseDir)
            val sink = RecordingSealedSink()
            val facts = mutableListOf<AudioSpoolFact>()

            val windowStart = 1_700_000_000_000L

            // Portion 1
            val p1Start = windowStart + 10_000L
            val p1End = windowStart + 30_000L
            val attemptDir1 = createAttemptDir(sourceDir, "rec-portion-1", windowStart, p1Start, p1End, mp4Content = "content-portion-1")

            val manager = ScreenRecoveryManager(
                screenSourceDir = sourceDir,
                spoolDir = spoolDir,
                occupiedLeaves = { _, _ -> occupied },
                lookup = AudioSpoolLookup { _, _, _, _, _, _, _, _ -> AudioCommitStatus.NotFound },
                sealedSink = { segment, result, epoch ->
                    sink.persistSealed(segment, result, epoch)
                    val leaf = result.directory?.fileName?.toString() ?: segment.key.segment
                    occupied += leaf
                    val mp4File = spoolDir.resolve(segment.key.day).resolve("observer").resolve(leaf).resolve("screen.mp4").toFile()
                    val sha = if (mp4File.exists()) sha256(mp4File.toPath()) else "sha-$epoch"
                    facts += AudioSpoolFact(
                        segmentId = "${segment.key.day}/observer/$leaf",
                        sha256 = sha,
                        captureStartEpochMs = segment.payloads.first().captureStartEpochMs,
                        captureEndEpochMs = segment.payloads.first().captureEndEpochMs,
                        sourceId = ScreenConstants.SOURCE_ID,
                        name = ScreenConstants.PAYLOAD_NAME,
                        spoolPayloadExists = true,
                        spoolPayloadShaMatches = true,
                        spoolCustodyDurable = true,
                    )
                },
                gaps = gaps,
                remuxer = FakeRemuxer(succeed = true, sampleDurationMs = 20_000L),
                factsProvider = { _, _, _, _ -> facts },
            )

            // Recover Portion 1
            manager.recover()
            assertFalse(attemptDir1.exists())
            assertEquals(1, sink.persisted.size)
            val segment1Name = sink.persisted[0].second.directory?.fileName?.toString() ?: sink.persisted[0].first.key.segment

            // Portion 2 in the same window with different capture bounds
            val p2Start = windowStart + 50_000L
            val p2End = windowStart + 80_000L
            val attemptDir2 = createAttemptDir(sourceDir, "rec-portion-2", windowStart, p2Start, p2End, mp4Content = "content-portion-2-different")

            // Recover Portion 2
            manager.recover()
            assertFalse(attemptDir2.exists())
            assertEquals(2, sink.persisted.size)
            val segment2Name = sink.persisted[1].second.directory?.fileName?.toString() ?: sink.persisted[1].first.key.segment

            assertEquals(app.solstone.core.segment.wireKeys(p1Start, p1End, java.time.ZoneId.of("UTC")).segment, segment1Name)
            assertEquals(app.solstone.core.segment.wireKeys(p2Start, p2End, java.time.ZoneId.of("UTC")).segment, segment2Name)
            assertTrue(segment2Name != segment1Name)
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun remuxThatThrowsKeepsTheDirectoryAndALaterSuccessDeliversWithoutALoss() {
        val baseDir = Files.createTempDirectory(Path.of("/var/tmp"), "screen-recovery-throw-").toFile()
        try {
            val sourceDir = File(baseDir, "screen-source").apply { mkdirs() }
            val spoolDir = File(baseDir, "spool").toPath().apply { Files.createDirectories(this) }
            val gaps = gapsOf(baseDir)
            val sink = RecordingSealedSink()
            var explode = true
            val remuxer = object : ScreenRemuxer {
                override fun remux(attemptDir: File, h264File: File, indexFile: File, recordFile: File, mp4File: File): ScreenRemuxResult {
                    if (explode) error("remux failed")
                    mp4File.writeText("playable")
                    return ScreenRemuxResult.Success(sampleDurationMs = 10_000L, outputBytes = mp4File.length())
                }
            }
            val attemptDir = createAttemptDir(sourceDir, "rec-throw", 1_700_000_000_000L, 1_700_000_010_000L, 1_700_000_020_000L)
            val manager = ScreenRecoveryManager(
                screenSourceDir = sourceDir,
                spoolDir = spoolDir,
                occupiedLeaves = { _, _ -> emptySet() },
                lookup = AudioSpoolLookup { _, _, _, _, _, _, _, _ -> AudioCommitStatus.NotFound },
                sealedSink = sink,
                gaps = gaps,
                remuxer = remuxer,
            )
            manager.recover()
            assertTrue(attemptDir.exists())
            assertEquals(listOf("remux failed"), rows(gaps).map { it.reason })
            assertTrue(rows(gaps).none { it.kind == "loss" })
            assertFalse(File(attemptDir, "gap-loss").exists())
            explode = false
            manager.recover()
            assertFalse(attemptDir.exists())
            assertEquals(1, sink.persisted.size)
            assertTrue(rows(gaps).none { it.kind == "loss" })
            assertTrue(rows(gaps).any { it.reason == "delivered" })
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun remuxFailureWithARealIndexSampleIsALossAndConfigurationOnlyIsNot() {
        val baseDir = Files.createTempDirectory(Path.of("/var/tmp"), "screen-recovery-index-").toFile()
        try {
            val sourceDir = File(baseDir, "screen-source").apply { mkdirs() }
            val spoolDir = File(baseDir, "spool").toPath().apply { Files.createDirectories(this) }
            val gaps = gapsOf(baseDir)
            val real = createAttemptDir(sourceDir, "rec-real", 1_700_000_000_000L, 1_700_000_010_000L, 1_700_000_020_000L)
            File(real, "samples.idx").writeText("0,100,0\n")
            val config = createAttemptDir(sourceDir, "rec-config", 1_700_000_100_000L, 1_700_000_110_000L, 1_700_000_120_000L)
            File(config, "samples.idx").writeText("0,10,4\n")
            val manager = ScreenRecoveryManager(
                screenSourceDir = sourceDir,
                spoolDir = spoolDir,
                occupiedLeaves = { _, _ -> emptySet() },
                lookup = AudioSpoolLookup { _, _, _, _, _, _, _, _ -> AudioCommitStatus.NotFound },
                sealedSink = RecordingSealedSink(),
                gaps = gaps,
                remuxer = FakeRemuxer(succeed = false),
            )
            manager.recover()
            assertTrue(real.exists())
            assertTrue(File(real, "gap-loss").isFile)
            assertEquals(listOf("undeliverable"), rows(gaps).filter { it.kind == "loss" }.map { it.reason })
            assertTrue(config.exists())
            assertFalse(File(config, "gap-loss").exists())
            assertTrue(rows(gaps).any { it.kind == "diagnostic" && it.reason == "no captured frame" && it.material == "rec-config" })
        } finally {
            baseDir.deleteRecursively()
        }
    }
}
