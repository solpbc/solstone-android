// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.audio

import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.sha256
import app.solstone.core.spool.SealResult
import app.solstone.core.spool.SealedSegmentSink
import app.solstone.core.spool.UnresolvedInterruption
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioRecoveryManagerTest {
    @Test
    fun recoversCompleteAttemptDirAndSealsDirectly() {
        val root = tempDirectory()
        val spoolDir = tempDirectory().toPath()
        val audioDir = File(root, "audio-source")
        val cacheDir = File(root, "cache-source")
        audioDir.mkdirs()

        val attemptId = "rec-test-1"
        val attemptDir = File(audioDir, attemptId).apply { mkdirs() }
        val windowStart = 1700000000000L
        val windowEnd = windowStart + 300_000L
        val info = AudioRecordInfo(
            id = attemptId,
            captureStartEpochMs = windowStart + 1000L,
            zoneId = "UTC",
            utcOffsetSeconds = 0,
            windowStartEpochMs = windowStart,
            windowEndEpochMs = windowEnd,
            openEndEpochMs = windowEnd,
        )
        File(attemptDir, "record.txt").writeText(info.serialize())
        File(attemptDir, "capture.adts").writeBytes("fake-adts-data".encodeToByteArray())

        val sink = FakeSealedSegmentSink()
        val interruption = FakeInterruption()
        val remuxer = FakeRemuxer(durationMs = 299_000L)
        val lookup = AudioSpoolLookup { _, _, _, _, _, _, _, _ -> AudioCommitStatus.NotFound }

        val manager = AudioRecoveryManager(
            audioSourceDir = audioDir,
            cacheAudioDir = cacheDir,
            spoolDir = spoolDir,
            occupiedLeaves = { _, _ -> emptySet() },
            lookup = lookup,
            sealedSink = sink,
            interruption = interruption,
            remuxer = remuxer,
            isReadableM4aProbe = { true },
        )

        manager.recover()

        assertFalse(attemptDir.exists(), "recovered attempt dir should be deleted after seal")
        assertFalse(interruption.marked)
        assertEquals(1, sink.persisted.size)
        val sealed = sink.persisted.single()
        assertEquals(1, sealed.payloads.size)
        val payload = sealed.payloads.single()
        assertEquals("audio.m4a", payload.ref.name)
        assertEquals(windowStart + 1000L + 299_000L, payload.captureEndEpochMs)
    }

    @Test
    fun subSecondRecoveredRecordingKeysWithOneSecondLen() {
        val sealed = recoverSingle(WINDOW_START, WINDOW_START, WINDOW_END, WINDOW_END, durationMs = 400L)
        assertEquals(1L, segmentLen(sealed.key.segment))
        assertEquals(sealed.key.segment, sealed.wireKeys.segment)
        assertEquals(WINDOW_START + 400L, sealed.payloads.single().captureEndEpochMs)
    }

    @Test
    fun recoveredRecordingPastWindowEndKeysAtOneWindowLen() {
        val sealed = recoverSingle(WINDOW_START, WINDOW_START, WINDOW_END, WINDOW_END + 1_500L, durationMs = 301_500L)
        assertEquals(300L, segmentLen(sealed.key.segment))
        assertEquals(sealed.key.segment, sealed.wireKeys.segment)
        assertEquals(WINDOW_START + 301_500L, sealed.payloads.single().captureEndEpochMs)
    }

    @Test
    fun recoveredRecordingKeepsItsCapturedLen() {
        val sealed = recoverSingle(WINDOW_START + 1_000L, WINDOW_START, WINDOW_END, WINDOW_END, durationMs = 120_400L)
        assertEquals("221321_120", sealed.key.segment)
        assertEquals(WINDOW_START + 121_400L, sealed.payloads.single().captureEndEpochMs)
    }

    @Test
    fun storedSampleDurationWithExistingM4aSkipsRemux() {
        val root = tempDirectory()
        val spoolDir = tempDirectory().toPath()
        val audioDir = File(root, "audio-source")
        val cacheDir = File(root, "cache-source")
        audioDir.mkdirs()

        val attemptId = "rec-stored-duration"
        val attemptDir = File(audioDir, attemptId).apply { mkdirs() }
        val windowStart = 1700000000000L
        val windowEnd = windowStart + 300_000L
        val info = AudioRecordInfo(
            id = attemptId,
            captureStartEpochMs = windowStart + 1000L,
            zoneId = "UTC",
            utcOffsetSeconds = 0,
            windowStartEpochMs = windowStart,
            windowEndEpochMs = windowEnd,
            openEndEpochMs = windowEnd,
            sampleDurationMs = 250_000L,
        )
        File(attemptDir, "record.txt").writeText(info.serialize())
        File(attemptDir, "audio.m4a").writeBytes("existing-m4a-bytes".encodeToByteArray())

        val sink = FakeSealedSegmentSink()
        val interruption = FakeInterruption()
        var remuxCalled = false
        val remuxer = object : AacAdtsRemuxer {
            override fun remux(adts: File, m4a: File): AacRemux {
                remuxCalled = true
                error("remuxer should not be called")
            }
        }
        val lookup = AudioSpoolLookup { _, _, _, _, _, _, _, _ -> AudioCommitStatus.NotFound }

        val manager = AudioRecoveryManager(
            audioSourceDir = audioDir,
            cacheAudioDir = cacheDir,
            spoolDir = spoolDir,
            occupiedLeaves = { _, _ -> emptySet() },
            lookup = lookup,
            sealedSink = sink,
            interruption = interruption,
            remuxer = remuxer,
            isReadableM4aProbe = { true },
        )

        manager.recover()

        assertFalse(remuxCalled)
        assertFalse(attemptDir.exists())
        assertFalse(interruption.marked)
        assertEquals(1, sink.persisted.size)
        val sealed = sink.persisted.single()
        assertEquals(windowStart + 1000L + 250_000L, sealed.payloads.single().captureEndEpochMs)
    }

    @Test
    fun existingM4aWithoutStoredDurationCallsRemuxerWhenAdtsExists() {
        val root = tempDirectory()
        val spoolDir = tempDirectory().toPath()
        val audioDir = File(root, "audio-source")
        val cacheDir = File(root, "cache-source")
        audioDir.mkdirs()

        val attemptId = "rec-stale-m4a"
        val attemptDir = File(audioDir, attemptId).apply { mkdirs() }
        val windowStart = 1700000000000L
        val windowEnd = windowStart + 300_000L
        val info = AudioRecordInfo(
            id = attemptId,
            captureStartEpochMs = windowStart + 1000L,
            zoneId = "UTC",
            utcOffsetSeconds = 0,
            windowStartEpochMs = windowStart,
            windowEndEpochMs = windowEnd,
            openEndEpochMs = windowEnd,
            sampleDurationMs = null,
        )
        File(attemptDir, "record.txt").writeText(info.serialize())
        File(attemptDir, "audio.m4a").writeBytes("stale-m4a".encodeToByteArray())
        File(attemptDir, "capture.adts").writeBytes("adts-bytes".encodeToByteArray())

        val sink = FakeSealedSegmentSink()
        val interruption = FakeInterruption()
        var remuxCalled = false
        val remuxer = object : AacAdtsRemuxer {
            override fun remux(adts: File, m4a: File): AacRemux {
                remuxCalled = true
                m4a.writeBytes("new-m4a-bytes".encodeToByteArray())
                return AacRemux(sampleDurationMs = 280_000L, outputBytes = m4a.length())
            }
        }
        val lookup = AudioSpoolLookup { _, _, _, _, _, _, _, _ -> AudioCommitStatus.NotFound }

        val manager = AudioRecoveryManager(
            audioSourceDir = audioDir,
            cacheAudioDir = cacheDir,
            spoolDir = spoolDir,
            occupiedLeaves = { _, _ -> emptySet() },
            lookup = lookup,
            sealedSink = sink,
            interruption = interruption,
            remuxer = remuxer,
            isReadableM4aProbe = { true },
        )

        manager.recover()

        assertTrue(remuxCalled)
        assertFalse(attemptDir.exists())
        assertEquals(1, sink.persisted.size)
        assertEquals(windowStart + 1000L + 280_000L, sink.persisted.single().payloads.single().captureEndEpochMs)
    }

    @Test
    fun thrownRemuxKeepsDirWithoutMarkOrSeal() {
        val root = tempDirectory()
        val spoolDir = tempDirectory().toPath()
        val audioDir = File(root, "audio-source")
        val cacheDir = File(root, "cache-source")
        audioDir.mkdirs()

        val attemptId = "rec-thrown-remux"
        val attemptDir = File(audioDir, attemptId).apply { mkdirs() }
        val windowStart = 1700000000000L
        val windowEnd = windowStart + 300_000L
        val info = AudioRecordInfo(
            id = attemptId,
            captureStartEpochMs = windowStart,
            zoneId = "UTC",
            utcOffsetSeconds = 0,
            windowStartEpochMs = windowStart,
            windowEndEpochMs = windowEnd,
        )
        File(attemptDir, "record.txt").writeText(info.serialize())
        File(attemptDir, "capture.adts").writeBytes("adts-data".encodeToByteArray())

        val sink = FakeSealedSegmentSink()
        val interruption = FakeInterruption()
        val remuxer = object : AacAdtsRemuxer {
            override fun remux(adts: File, m4a: File): AacRemux {
                throw RuntimeException("codec explosion")
            }
        }
        val lookup = AudioSpoolLookup { _, _, _, _, _, _, _, _ -> AudioCommitStatus.NotFound }

        val manager = AudioRecoveryManager(
            audioSourceDir = audioDir,
            cacheAudioDir = cacheDir,
            spoolDir = spoolDir,
            occupiedLeaves = { _, _ -> emptySet() },
            lookup = lookup,
            sealedSink = sink,
            interruption = interruption,
            remuxer = remuxer,
            isReadableM4aProbe = { true },
        )

        manager.recover()

        assertTrue(attemptDir.exists(), "directory should be kept on thrown remux")
        assertFalse(interruption.marked, "interruption should not be marked on thrown remux")
        assertTrue(sink.persisted.isEmpty(), "segment should not be sealed on thrown remux")
    }

    @Test
    fun inFlightAttemptIsSkippedDuringRecovery() {
        val root = tempDirectory()
        val spoolDir = tempDirectory().toPath()
        val audioDir = File(root, "audio-source")
        audioDir.mkdirs()

        val attemptId = "rec-in-flight"
        val attemptDir = File(audioDir, attemptId).apply { mkdirs() }
        val windowStart = 1700000000000L
        val windowEnd = windowStart + 300_000L
        val info = AudioRecordInfo(
            id = attemptId,
            captureStartEpochMs = windowStart,
            zoneId = "UTC",
            utcOffsetSeconds = 0,
            windowStartEpochMs = windowStart,
            windowEndEpochMs = windowEnd,
        )
        File(attemptDir, "record.txt").writeText(info.serialize())
        File(attemptDir, "capture.adts").writeBytes("fake-adts-data".encodeToByteArray())

        val sink = FakeSealedSegmentSink()
        val interruption = FakeInterruption()
        val remuxer = FakeRemuxer(durationMs = 300_000L)
        val lookup = AudioSpoolLookup { _, _, _, _, _, _, _, _ -> AudioCommitStatus.NotFound }

        val manager = AudioRecoveryManager(
            audioSourceDir = audioDir,
            cacheAudioDir = File(root, "cache-source"),
            spoolDir = spoolDir,
            occupiedLeaves = { _, _ -> emptySet() },
            lookup = lookup,
            sealedSink = sink,
            interruption = interruption,
            remuxer = remuxer,
            isReadableM4aProbe = { true },
        )

        manager.recover(inFlightIds = setOf(attemptId))

        assertTrue(attemptDir.exists(), "in-flight attempt dir should be preserved")
        assertTrue(sink.persisted.isEmpty())
        assertFalse(interruption.marked)
    }

    @Test
    fun emptyOrFailedRemuxMarksInterruptionAndDeletesDir() {
        val root = tempDirectory()
        val spoolDir = tempDirectory().toPath()
        val audioDir = File(root, "audio-source")
        audioDir.mkdirs()

        val attemptId = "rec-corrupt"
        val attemptDir = File(audioDir, attemptId).apply { mkdirs() }
        val windowStart = 1700000000000L
        val windowEnd = windowStart + 300_000L
        val info = AudioRecordInfo(
            id = attemptId,
            captureStartEpochMs = windowStart,
            zoneId = "UTC",
            utcOffsetSeconds = 0,
            windowStartEpochMs = windowStart,
            windowEndEpochMs = windowEnd,
        )
        File(attemptDir, "record.txt").writeText(info.serialize())
        File(attemptDir, "capture.adts").writeBytes(ByteArray(0))

        val sink = FakeSealedSegmentSink()
        val interruption = FakeInterruption()
        val remuxer = FakeRemuxer(durationMs = 0L)
        val lookup = AudioSpoolLookup { _, _, _, _, _, _, _, _ -> AudioCommitStatus.NotFound }

        val manager = AudioRecoveryManager(
            audioSourceDir = audioDir,
            cacheAudioDir = File(root, "cache-source"),
            spoolDir = spoolDir,
            occupiedLeaves = { _, _ -> emptySet() },
            lookup = lookup,
            sealedSink = sink,
            interruption = interruption,
            remuxer = remuxer,
            isReadableM4aProbe = { true },
        )

        manager.recover()

        assertFalse(attemptDir.exists(), "failed attempt dir should be deleted")
        assertTrue(interruption.marked, "failed attempt should mark unresolved interruption")
        assertTrue(sink.persisted.isEmpty())
    }

    @Test
    fun legacyCoordinatesMatchDeletesCacheFileWithoutMark() {
        val root = tempDirectory()
        val spoolDir = tempDirectory().toPath()
        val audioDir = File(root, "audio-source").apply { mkdirs() }
        val legacyDir = File(root, "cache/audio-source").apply { mkdirs() }
        val legacyFile = File(legacyDir, "audio-1700000000000.m4a").apply {
            writeBytes("matching-legacy-bytes".encodeToByteArray())
        }

        val interruption = FakeInterruption()
        val manager = AudioRecoveryManager(
            audioSourceDir = audioDir,
            cacheAudioDir = legacyDir,
            spoolDir = spoolDir,
            occupiedLeaves = { _, _ -> emptySet() },
            lookup = object : AudioSpoolLookup {
                override fun findStatus(
                    sha256: String,
                    captureStartEpochMs: Long,
                    captureEndEpochMs: Long,
                    sourceId: String,
                    name: String,
                    day: String,
                    stream: String,
                    segmentLeaf: String,
                ): AudioCommitStatus = AudioCommitStatus.NotFound

                override fun legacyManifestCoordinatesMatch(sha256: String): Boolean = true
            },
            sealedSink = FakeSealedSegmentSink(),
            interruption = interruption,
            remuxer = FakeRemuxer(0L),
            isReadableM4aProbe = { true },
        )

        manager.recover()

        assertFalse(legacyFile.exists(), "matching legacy file should be deleted")
        assertFalse(interruption.marked, "matching legacy file should not mark interruption")
    }

    @Test
    fun legacyUncoordinatedFileMarksAndCopiesUnderLegacyShaDirectory() {
        val root = tempDirectory()
        val spoolDir = tempDirectory().toPath()
        val audioDir = File(root, "audio-source").apply { mkdirs() }
        val legacyDir = File(root, "cache/audio-source").apply { mkdirs() }
        val bytes = "legacy-m4a-bytes".encodeToByteArray()
        val legacyFile = File(legacyDir, "audio-1700000000000.m4a").apply {
            writeBytes(bytes)
        }
        val fileSha = sha256(legacyFile.toPath())

        val interruption = FakeInterruption()
        val manager = AudioRecoveryManager(
            audioSourceDir = audioDir,
            cacheAudioDir = legacyDir,
            spoolDir = spoolDir,
            occupiedLeaves = { _, _ -> emptySet() },
            lookup = object : AudioSpoolLookup {
                override fun findStatus(
                    sha256: String,
                    captureStartEpochMs: Long,
                    captureEndEpochMs: Long,
                    sourceId: String,
                    name: String,
                    day: String,
                    stream: String,
                    segmentLeaf: String,
                ): AudioCommitStatus = AudioCommitStatus.NotFound

                override fun legacyManifestCoordinatesMatch(sha256: String): Boolean = false
            },
            sealedSink = FakeSealedSegmentSink(),
            interruption = interruption,
            remuxer = FakeRemuxer(0L),
            isReadableM4aProbe = { true },
        )

        manager.recover()

        assertFalse(legacyFile.exists(), "legacy audio file should be removed from cache after copy")
        assertTrue(interruption.marked, "uncoordinated legacy file should mark interruption")
        val preserved = File(audioDir, "legacy/$fileSha/audio-1700000000000.m4a")
        assertTrue(preserved.exists(), "file should be preserved under legacy/<sha>/")
        assertEquals(bytes.decodeToString(), preserved.readText())
    }

    @Test
    fun legacyPreserveFailsClosedWhenMarkUnresolvedFails() {
        val root = tempDirectory()
        val spoolDir = tempDirectory().toPath()
        val audioDir = File(root, "audio-source").apply { mkdirs() }
        val legacyDir = File(root, "cache/audio-source").apply { mkdirs() }
        val legacyFile = File(legacyDir, "audio-1700000000000.m4a").apply {
            writeBytes("legacy-m4a-bytes".encodeToByteArray())
        }

        val interruption = object : UnresolvedInterruption {
            override fun markUnresolved(): Boolean = false
            override fun isUnresolved(): Boolean = false
        }
        val manager = AudioRecoveryManager(
            audioSourceDir = audioDir,
            cacheAudioDir = legacyDir,
            spoolDir = spoolDir,
            occupiedLeaves = { _, _ -> emptySet() },
            lookup = object : AudioSpoolLookup {
                override fun findStatus(
                    sha256: String,
                    captureStartEpochMs: Long,
                    captureEndEpochMs: Long,
                    sourceId: String,
                    name: String,
                    day: String,
                    stream: String,
                    segmentLeaf: String,
                ): AudioCommitStatus = AudioCommitStatus.NotFound

                override fun legacyManifestCoordinatesMatch(sha256: String): Boolean = false
            },
            sealedSink = FakeSealedSegmentSink(),
            interruption = interruption,
            remuxer = FakeRemuxer(0L),
            isReadableM4aProbe = { true },
        )

        manager.recover()

        assertTrue(legacyFile.exists(), "legacy file should be kept if markUnresolved fails")
    }

    @Test
    fun classifyAudioAttemptMatrix() {
        val wireDay = "20261003"
        val wireStream = "main"
        val wireLeaf = "0000"
        val wireId = "$wireDay/$wireStream/$wireLeaf"
        val sha = "sha-test"
        val start = 1000L
        val end = 2000L

        // 1. Sha-only Room match with different capture bounds is NotFound
        val shaOnlyMatch = listOf(
            AudioSpoolFact(
                segmentId = "other-seg",
                sha256 = sha,
                captureStartEpochMs = 3000L,
                captureEndEpochMs = 4000L,
                sourceId = "audio",
                name = "audio.m4a",
                spoolPayloadExists = true,
                spoolPayloadShaMatches = true,
            ),
        )
        assertEquals(
            AudioCommitStatus.NotFound,
            classifyAudioAttempt(sha, start, end, "audio", "audio.m4a", wireDay, wireStream, wireLeaf, shaOnlyMatch),
        )

        // 2. sha + bounds + missing spool file (EVICTED) is a commit
        val evictedFact = listOf(
            AudioSpoolFact(
                segmentId = "other-seg",
                sha256 = sha,
                captureStartEpochMs = start,
                captureEndEpochMs = end,
                sourceId = "audio",
                name = "audio.m4a",
                spoolPayloadExists = false,
                spoolPayloadShaMatches = false,
                confirmedUploaded = true,
            ),
        )
        assertEquals(
            AudioCommitStatus.CommittedMatch,
            classifyAudioAttempt(sha, start, end, "audio", "audio.m4a", wireDay, wireStream, wireLeaf, evictedFact),
        )
        assertEquals(
            AudioCommitStatus.CustodyUnproven,
            classifyAudioAttempt(sha, start, end, "audio", "audio.m4a", wireDay, wireStream, wireLeaf,
                evictedFact.map { it.copy(confirmedUploaded = false) }),
        )
        assertEquals(AudioCommitStatus.CommittedMatch,
            classifyAudioAttempt(sha, start, end, "audio", "audio.m4a", wireDay, wireStream, wireLeaf,
                evictedFact.map { it.copy(spoolPayloadExists = true, spoolPayloadShaMatches = false) }))

        // A readable matching final still needs durable publication.
        val localMatch = evictedFact.single().copy(
            confirmedUploaded = false, spoolPayloadExists = true, spoolPayloadShaMatches = true,
        )
        assertEquals(AudioCommitStatus.CustodyUnproven,
            classifyAudioAttempt(sha, start, end, "audio", "audio.m4a", wireDay, wireStream, wireLeaf, listOf(localMatch)))
        assertEquals(AudioCommitStatus.CommittedMatch,
            classifyAudioAttempt(sha, start, end, "audio", "audio.m4a", wireDay, wireStream, wireLeaf,
                listOf(localMatch.copy(spoolCustodyDurable = true))))

        // 3. same bounds with a different sha is a collision
        val differentShaSameBounds = listOf(
            AudioSpoolFact(
                segmentId = "other-seg",
                sha256 = "different-sha",
                captureStartEpochMs = start,
                captureEndEpochMs = end,
                sourceId = "audio",
                name = "audio.m4a",
                spoolPayloadExists = true,
                spoolPayloadShaMatches = true,
            ),
        )
        assertEquals(
            AudioCommitStatus.IdentityCollisionDifferentBytes,
            classifyAudioAttempt(sha, start, end, "audio", "audio.m4a", wireDay, wireStream, wireLeaf, differentShaSameBounds),
        )

        // 4. spool file present with a different hash is collision / not a commit
        val hashMismatchFact = listOf(
            AudioSpoolFact(
                segmentId = "other-seg",
                sha256 = sha,
                captureStartEpochMs = start,
                captureEndEpochMs = end,
                sourceId = "audio",
                name = "audio.m4a",
                spoolPayloadExists = true,
                spoolPayloadShaMatches = false,
            ),
        )
        assertEquals(
            AudioCommitStatus.IdentityCollisionDifferentBytes,
            classifyAudioAttempt(sha, start, end, "audio", "audio.m4a", wireDay, wireStream, wireLeaf, hashMismatchFact),
        )
    }

    @Test
    fun legacyCacheAlreadyDeliveredMatrix() {
        val sha = "sha-legacy"
        val start = 1000L
        val end = 2000L

        // Valid delivered fact
        val validFact = AudioSpoolFact(
            segmentId = "seg-1",
            sha256 = sha,
            captureStartEpochMs = start,
            captureEndEpochMs = end,
            sourceId = "audio",
            name = "audio.m4a",
            spoolPayloadExists = true,
            spoolPayloadShaMatches = true,
            zoneId = "UTC",
            spoolCustodyDurable = true,
        )
        assertTrue(legacyCacheAlreadyDelivered(sha, listOf(validFact)))
        assertFalse(legacyCacheAlreadyDelivered(sha, listOf(validFact.copy(spoolCustodyDurable = false))))
        assertFalse(legacyCacheAlreadyDelivered(sha, listOf(validFact.copy(sourceId = "camera"))))

        // No zone / blank zone -> false
        assertFalse(legacyCacheAlreadyDelivered(sha, listOf(validFact.copy(zoneId = null))))
        assertFalse(legacyCacheAlreadyDelivered(sha, listOf(validFact.copy(zoneId = ""))))

        // Spool payload missing -> false
        assertFalse(legacyCacheAlreadyDelivered(sha, listOf(validFact.copy(spoolPayloadExists = false))))

        // Spool payload hash mismatch -> false
        assertFalse(legacyCacheAlreadyDelivered(sha, listOf(validFact.copy(spoolPayloadShaMatches = false))))

        // captureEnd <= captureStart -> false
        assertFalse(legacyCacheAlreadyDelivered(sha, listOf(validFact.copy(captureEndEpochMs = start))))

        // Different sha -> false
        assertFalse(legacyCacheAlreadyDelivered("other-sha", listOf(validFact)))
    }

    private fun recoverSingle(
        captureStartEpochMs: Long,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long,
        openEndEpochMs: Long,
        durationMs: Long,
    ): SealedSegment {
        val root = tempDirectory()
        val audioDir = File(root, "audio-source")
        val attemptId = "rec-len-test"
        val attemptDir = File(audioDir, attemptId).apply { mkdirs() }
        val info = AudioRecordInfo(
            id = attemptId,
            captureStartEpochMs = captureStartEpochMs,
            zoneId = "UTC",
            utcOffsetSeconds = 0,
            windowStartEpochMs = windowStartEpochMs,
            windowEndEpochMs = windowEndEpochMs,
            openEndEpochMs = openEndEpochMs,
        )
        File(attemptDir, "record.txt").writeText(info.serialize())
        File(attemptDir, "capture.adts").writeBytes("fake-adts-data".encodeToByteArray())
        val sink = FakeSealedSegmentSink()
        val interruption = FakeInterruption()

        AudioRecoveryManager(
            audioSourceDir = audioDir,
            cacheAudioDir = File(root, "cache-source"),
            spoolDir = tempDirectory().toPath(),
            occupiedLeaves = { _, _ -> emptySet() },
            lookup = AudioSpoolLookup { _, _, _, _, _, _, _, _ -> AudioCommitStatus.NotFound },
            sealedSink = sink,
            interruption = interruption,
            remuxer = FakeRemuxer(durationMs = durationMs),
            isReadableM4aProbe = { true },
        ).recover()

        assertFalse(interruption.marked)
        assertFalse(attemptDir.exists())
        return sink.persisted.single()
    }

    private fun segmentLen(segment: String): Long = segment.substringAfter('_').toLong()

    private fun tempDirectory(): File = Files.createTempDirectory("recovery-test").toFile()

    private class FakeRemuxer(private val durationMs: Long) : AacAdtsRemuxer {
        override fun remux(adts: File, m4a: File): AacRemux {
            if (!adts.exists() || adts.length() == 0L || durationMs <= 0L) {
                return AacRemux(0L, 0L)
            }
            m4a.writeBytes(adts.readBytes())
            return AacRemux(sampleDurationMs = durationMs, outputBytes = m4a.length())
        }
    }

    private class FakeInterruption : UnresolvedInterruption {
        var marked = false
        override fun markUnresolved(): Boolean {
            marked = true
            return true
        }

        override fun isUnresolved(): Boolean = marked
    }

    private class FakeSealedSegmentSink : SealedSegmentSink {
        val persisted = mutableListOf<SealedSegment>()
        override fun persistSealed(segment: SealedSegment, result: SealResult, sealedAtEpochMs: Long) {
            persisted += segment
        }
    }

    private companion object {
        const val WINDOW_START = 1_700_000_000_000L
        const val WINDOW_END = WINDOW_START + 300_000L
    }
}
