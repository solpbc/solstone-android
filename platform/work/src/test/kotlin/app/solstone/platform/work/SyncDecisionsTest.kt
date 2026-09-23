// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.observer.IngestDescriptors
import app.solstone.core.observer.IngestFileDescriptor
import app.solstone.core.observer.IngestOutcome
import app.solstone.core.observer.ReconcileVerdict
import app.solstone.core.queue.RetryDecision
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncDecisionsTest {
    @Test
    fun selectDrainSegmentsKeepsDueMainStreamRowsAndExcludesOtherStreams() {
        val now = 2_000_000L
        val rows = listOf(
            segment("main-sealed", MAIN_STREAM, QueueState.SEALED),
            segment("observer-location", MAIN_STREAM, QueueState.SEALED),
            segment("import-sealed", "import.share", QueueState.SEALED),
            segment("main-uploading", MAIN_STREAM, QueueState.UPLOADING),
            segment("main-failed-due", MAIN_STREAM, QueueState.FAILED, attemptCount = 1, lastAttemptAt = 0, lastStatusCode = 500),
            segment(
                "main-failed-backoff",
                MAIN_STREAM,
                QueueState.FAILED,
                attemptCount = 1,
                lastAttemptAt = now - 1_000,
                lastStatusCode = 500,
            ),
        )

        assertEquals(
            listOf("main-sealed", "observer-location", "main-uploading", "main-failed-due"),
            selectDrainSegments(rows, now).map { it.id },
        )
    }

    @Test
    fun isRetryDueCoversStateAndBackoffPolicy() {
        val now = 10 * HOUR_MS

        assertTrue(isRetryDue(QueueState.SEALED, attemptCount = 0, lastAttemptAt = null, lastStatusCode = null, now = now))
        assertTrue(isRetryDue(QueueState.UPLOADING, attemptCount = 0, lastAttemptAt = null, lastStatusCode = null, now = now))
        assertTrue(isRetryDue(QueueState.FAILED, attemptCount = 1, lastAttemptAt = now - 15 * MINUTE_MS, lastStatusCode = 500, now = now))
        assertFalse(isRetryDue(QueueState.FAILED, attemptCount = 1, lastAttemptAt = now - 14 * MINUTE_MS, lastStatusCode = 500, now = now))
        assertTrue(isRetryDue(QueueState.FAILED, attemptCount = 1, lastAttemptAt = now - 2 * HOUR_MS, lastStatusCode = 422, now = now))
        assertFalse(isRetryDue(QueueState.FAILED, attemptCount = 1, lastAttemptAt = now - HOUR_MS, lastStatusCode = 422, now = now))
        assertFalse(isRetryDue(QueueState.FAILED, attemptCount = 10, lastAttemptAt = 0, lastStatusCode = 401, now = now))
        assertFalse(isRetryDue(QueueState.FAILED, attemptCount = 1, lastAttemptAt = 0, lastStatusCode = 500, lastError = "removed_in_journal", now = now))
        assertFalse(isRetryDue(QueueState.SEALED, attemptCount = 0, lastAttemptAt = null, lastStatusCode = null, lastError = "removed_in_journal", now = now))
        assertFalse(isRetryDue(QueueState.UPLOADED, attemptCount = 0, lastAttemptAt = null, lastStatusCode = null, now = now))
    }

    @Test
    fun retryBackoffMsUsesApprovedBoundaries() {
        assertEquals(15 * MINUTE_MS, retryBackoffMs(1, RetryDecision.RETRY))
        assertEquals(30 * MINUTE_MS, retryBackoffMs(2, RetryDecision.RETRY))
        assertEquals(HOUR_MS, retryBackoffMs(3, RetryDecision.RETRY))
        assertEquals(2 * HOUR_MS, retryBackoffMs(4, RetryDecision.RETRY))
        assertEquals(4 * HOUR_MS, retryBackoffMs(5, RetryDecision.RETRY))
        assertEquals(4 * HOUR_MS, retryBackoffMs(99, RetryDecision.RETRY))

        assertEquals(2 * HOUR_MS, retryBackoffMs(1, RetryDecision.HARD_FAIL))
        assertEquals(4 * HOUR_MS, retryBackoffMs(2, RetryDecision.HARD_FAIL))
        assertEquals(6 * HOUR_MS, retryBackoffMs(3, RetryDecision.HARD_FAIL))
        assertEquals(6 * HOUR_MS, retryBackoffMs(99, RetryDecision.HARD_FAIL))
        assertEquals(Long.MAX_VALUE, retryBackoffMs(1, RetryDecision.STOP_AUTH))
    }

    @Test
    fun reconstructManifestUsesRowsAndEmptyGaps() {
        val manifest = reconstructManifest(
            segment = segment("seg-a", MAIN_STREAM, QueueState.SEALED, day = "20260617", key = "120000_2"),
            files = listOf(
                file("seg-a", "audio", "audio.bin", "sha-audio"),
                file("seg-a", "camera", "image.jpg", "sha-image"),
            ),
        )

        assertEquals(SegmentKey("20260617", "120000_2"), manifest.key)
        assertTrue(manifest.gaps.isEmpty())
        assertEquals(
            mapOf("audio.bin" to "sha-audio", "image.jpg" to "sha-image"),
            manifest.files.associate { it.name to it.sha256 },
        )
    }

    @Test
    fun reachabilityDecisionCoversAllCases() {
        assertEquals(ReachabilityVerdict.SKIP, decideReachability(paired = false, reachable = false))
        assertEquals(ReachabilityVerdict.RESCHEDULE, decideReachability(paired = true, reachable = false))
        assertEquals(ReachabilityVerdict.DRAIN, decideReachability(paired = true, reachable = true))
    }

    @Test
    fun receiptErrorAndResolveIngestOutcomesCoverAllCases() {
        val file1 = BundleFile("mic", "audio.wav", "sha-a", 10, "audio/wav", 1, 2)
        val file2 = BundleFile("mic", "notes.txt", "sha-b", 20, "text/plain", 1, 2)
        val manifest = BundleManifest(SegmentKey("20260616", "093000_60"), listOf(file1), emptyList())

        fun outcome(descriptors: IngestDescriptors): List<IngestOutcome> =
            listOf(IngestOutcome.Accepted("srv", descriptors))

        // sha mismatch
        val shaMismatch = outcome(IngestDescriptors.Listed(listOf(IngestFileDescriptor("audio.wav", "audio.wav", 10, "sha-wrong", "written"))))
        assertEquals(SegmentSyncResult.Retry(429, "custody_mismatch"), resolveIngestOutcomes(manifest, shaMismatch))

        // size mismatch
        val sizeMismatch = outcome(IngestDescriptors.Listed(listOf(IngestFileDescriptor("audio.wav", "audio.wav", 11, "sha-a", "written"))))
        assertEquals(SegmentSyncResult.Retry(429, "custody_mismatch"), resolveIngestOutcomes(manifest, sizeMismatch))

        // received_not_written
        val notWritten = outcome(IngestDescriptors.Listed(listOf(IngestFileDescriptor("audio.wav", "audio.wav", 10, "sha-a", "received_not_written"))))
        assertEquals(SegmentSyncResult.HardFail(422, "custody_not_written"), resolveIngestOutcomes(manifest, notWritten))

        // received_not_written precedence over mismatch in same POST
        val twoFileManifest = BundleManifest(SegmentKey("20260616", "093000_60"), listOf(file1, file2), emptyList())
        val notWrittenAndMismatch = listOf(
            IngestOutcome.Accepted(
                "srv",
                IngestDescriptors.Listed(
                    listOf(
                        IngestFileDescriptor("audio.wav", "audio.wav", 10, "sha-a", "received_not_written"),
                        IngestFileDescriptor("notes.txt", "notes.txt", 999, "sha-wrong", "unknown_disp"),
                    ),
                ),
            ),
        )
        assertEquals(SegmentSyncResult.HardFail(422, "custody_not_written"), resolveIngestOutcomes(twoFileManifest, notWrittenAndMismatch))

        // received_not_written precedence over extra submitted name in same POST
        val notWrittenAndExtraName = listOf(
            IngestOutcome.Accepted(
                "srv",
                IngestDescriptors.Listed(
                    listOf(
                        IngestFileDescriptor("audio.wav", "audio.wav", 10, "sha-a", "received_not_written"),
                        IngestFileDescriptor("extra.bin", "extra.bin", 5, "sha", "written"),
                    ),
                ),
            ),
        )
        assertEquals(SegmentSyncResult.HardFail(422, "custody_not_written"), resolveIngestOutcomes(manifest, notWrittenAndExtraName))

        // missing descriptor
        val missingDesc = listOf(
            IngestOutcome.Accepted(
                "srv",
                IngestDescriptors.Listed(listOf(IngestFileDescriptor("audio.wav", "audio.wav", 10, "sha-a", "written"))),
            ),
        )
        assertEquals(SegmentSyncResult.Retry(429, "custody_mismatch"), resolveIngestOutcomes(twoFileManifest, missingDesc))

        // extra descriptor
        val extraDesc = outcome(
            IngestDescriptors.Listed(
                listOf(
                    IngestFileDescriptor("audio.wav", "audio.wav", 10, "sha-a", "written"),
                    IngestFileDescriptor("extra.bin", "extra.bin", 5, "sha", "written"),
                ),
            ),
        )
        assertEquals(SegmentSyncResult.Retry(429, "custody_mismatch"), resolveIngestOutcomes(manifest, extraDesc))

        // duplicate submitted
        val dupSubmitted = outcome(
            IngestDescriptors.Listed(
                listOf(
                    IngestFileDescriptor("audio.wav", "audio.wav", 10, "sha-a", "written"),
                    IngestFileDescriptor("audio.wav", "audio.wav", 10, "sha-a", "written"),
                ),
            ),
        )
        assertEquals(SegmentSyncResult.Retry(429, "custody_mismatch"), resolveIngestOutcomes(manifest, dupSubmitted))

        // missing disposition
        val missingDisp = outcome(IngestDescriptors.Listed(listOf(IngestFileDescriptor("audio.wav", "audio.wav", 10, "sha-a", null))))
        assertEquals(SegmentSyncResult.Retry(429, "custody_mismatch"), resolveIngestOutcomes(manifest, missingDisp))

        // unknown disposition
        val unknownDisp = outcome(IngestDescriptors.Listed(listOf(IngestFileDescriptor("audio.wav", "audio.wav", 10, "sha-a", "quarantined"))))
        assertEquals(SegmentSyncResult.Retry(429, "custody_mismatch"), resolveIngestOutcomes(manifest, unknownDisp))

        // file_descriptors absent
        assertEquals(SegmentSyncResult.Retry(408, "custody_missing"), resolveIngestOutcomes(manifest, outcome(IngestDescriptors.Absent)))

        // file_descriptors: []
        assertEquals(SegmentSyncResult.Retry(429, "custody_mismatch"), resolveIngestOutcomes(manifest, outcome(IngestDescriptors.Listed(emptyList()))))

        // file_descriptors a JSON object / not a list
        assertEquals(SegmentSyncResult.Retry(429, "custody_mismatch"), resolveIngestOutcomes(manifest, outcome(IngestDescriptors.NotAList)))

        // written != submitted while submitted, size, sha256 match -> Uploaded
        val writtenDiff = outcome(IngestDescriptors.Listed(listOf(IngestFileDescriptor("audio.wav", "renamed.wav", 10, "sha-a", "written"))))
        assertEquals(SegmentSyncResult.Uploaded, resolveIngestOutcomes(manifest, writtenDiff))

        // disposition == already_held -> Uploaded
        val alreadyHeld = outcome(IngestDescriptors.Listed(listOf(IngestFileDescriptor("audio.wav", "audio.wav", 10, "sha-a", "already_held"))))
        assertEquals(SegmentSyncResult.Uploaded, resolveIngestOutcomes(manifest, alreadyHeld))

        // uppercase sha256 -> custody_mismatch
        val upperSha = outcome(IngestDescriptors.Listed(listOf(IngestFileDescriptor("audio.wav", "audio.wav", 10, "SHA-A", "written"))))
        assertEquals(SegmentSyncResult.Retry(429, "custody_mismatch"), resolveIngestOutcomes(manifest, upperSha))

        // descriptor matches only on written -> custody_mismatch
        val matchOnlyWritten = outcome(IngestDescriptors.Listed(listOf(IngestFileDescriptor("other.wav", "audio.wav", 10, "sha-a", "written"))))
        assertEquals(SegmentSyncResult.Retry(429, "custody_mismatch"), resolveIngestOutcomes(manifest, matchOnlyWritten))

        // Duplicate(null) with matching descriptor -> Uploaded
        val dupNullMatching = listOf(IngestOutcome.Duplicate(null, IngestDescriptors.Listed(listOf(IngestFileDescriptor("audio.wav", "audio.wav", 10, "sha-a", "written")))))
        assertEquals(SegmentSyncResult.Uploaded, resolveIngestOutcomes(manifest, dupNullMatching))

        // Duplicate(null) with absent descriptors -> custody_missing
        val dupNullAbsent = listOf(IngestOutcome.Duplicate(null, IngestDescriptors.Absent))
        assertEquals(SegmentSyncResult.Retry(408, "custody_missing"), resolveIngestOutcomes(manifest, dupNullAbsent))

        // Same-rank first wins: mismatch then missing -> mismatch / 429
        val multiSourceManifest = BundleManifest(
            SegmentKey("20260616", "093000_60"),
            listOf(
                BundleFile("mic", "audio.wav", "sha-a", 10, "audio/wav", 1, 2),
                BundleFile("cam", "photo.jpg", "sha-p", 20, "image/jpeg", 1, 2),
            ),
            emptyList(),
        )
        val mismatchThenMissing = listOf(
            IngestOutcome.Accepted("srv", IngestDescriptors.NotAList),
            IngestOutcome.Accepted("srv", IngestDescriptors.Absent),
        )
        assertEquals(SegmentSyncResult.Retry(429, "custody_mismatch"), resolveIngestOutcomes(multiSourceManifest, mismatchThenMissing))

        // Cross-rank: mismatch then received_not_written -> custody_not_written / 422
        val mismatchThenNotWritten = listOf(
            IngestOutcome.Accepted("srv", IngestDescriptors.NotAList),
            IngestOutcome.Accepted("srv", IngestDescriptors.Listed(listOf(IngestFileDescriptor("photo.jpg", "photo.jpg", 20, "sha-p", "received_not_written")))),
        )
        assertEquals(SegmentSyncResult.HardFail(422, "custody_not_written"), resolveIngestOutcomes(multiSourceManifest, mismatchThenNotWritten))
    }

    @Test
    fun ingestOutcomesMapToSyncResults() {
        val manifest = BundleManifest(
            SegmentKey("20260617", "120000_10"),
            listOf(BundleFile("mic", "audio.wav", "sha", 1, "audio/wav", 1, 2)),
            emptyList(),
        )
        assertEquals(SegmentSyncResult.HardFail(200, "hard failure"), resolveIngestOutcomes(manifest, listOf(IngestOutcome.Failed(null))))
        assertEquals(SegmentSyncResult.Retry(null, "retry"), resolveIngestOutcomes(manifest, listOf(IngestOutcome.UnknownStatus("future"))))
        assertEquals(SegmentSyncResult.Retry(null, "retry"), resolveIngestOutcomes(manifest, listOf(IngestOutcome.MalformedResponse("invalid_json"))))
        assertEquals(SegmentSyncResult.AuthHalt(401), resolveIngestOutcomes(manifest, listOf(IngestOutcome.Rejected(401, ""))))
        assertEquals(SegmentSyncResult.AuthHalt(403), resolveIngestOutcomes(manifest, listOf(IngestOutcome.Rejected(403, ""))))
        assertEquals(SegmentSyncResult.Retry(500, "retry"), resolveIngestOutcomes(manifest, listOf(IngestOutcome.Rejected(500, ""))))
        assertEquals(SegmentSyncResult.HardFail(404, "hard failure"), resolveIngestOutcomes(manifest, listOf(IngestOutcome.Rejected(404, ""))))
        assertEquals(SegmentSyncResult.HardFail(400, "hard failure"), resolveIngestOutcomes(manifest, listOf(IngestOutcome.Rejected(400, ""))))
        assertEquals(SegmentSyncResult.Retry(null, "retry"), resolveIoError())
    }

    @Test
    fun haltsDrainOnlyForAuthHalt() {
        assertTrue(haltsDrain(SegmentSyncResult.AuthHalt(401)))
        assertFalse(haltsDrain(SegmentSyncResult.Uploaded))
        assertFalse(haltsDrain(SegmentSyncResult.Retry(500)))
        assertFalse(haltsDrain(SegmentSyncResult.HardFail(400)))
        assertFalse(haltsDrain(SegmentSyncResult.JournalRemoved(500)))
    }

    @Test
    fun nonAuthFailuresDoNotHaltLaterSegments() {
        val results = listOf(
            SegmentSyncResult.Uploaded,
            SegmentSyncResult.Retry(500),
            SegmentSyncResult.HardFail(400),
            SegmentSyncResult.JournalRemoved(500),
            SegmentSyncResult.Uploaded,
        )

        assertEquals(listOf(false, false, false, false, false), results.map(::haltsDrain))
    }

    @Test
    fun nextSyncStateBuildsSingletonRow() {
        val row = nextSyncState(pendingCount = 3, lastSuccessAt = 10, lastFailureAt = 20)

        assertEquals(0, row.id)
        assertEquals(3, row.pendingCount)
        assertEquals(10, row.lastSuccessAt)
        assertEquals(20, row.lastFailureAt)
    }

    @Test
    fun planDayDrainSkipsKnownRemoteSegmentsAndUploadsMissingOnes() {
        val segments = listOf(
            segment("skip-id", MAIN_STREAM, QueueState.SEALED, key = "120000_1"),
            segment("upload-id", MAIN_STREAM, QueueState.SEALED, key = "120500_1"),
        )

        val actions = planDayDrain(
            verdicts = listOf(
                ReconcileVerdict(SegmentKey(DAY, "120000_1"), needsUpload = false),
                ReconcileVerdict(SegmentKey(DAY, "120500_1"), needsUpload = true),
            ),
            segments = segments,
        )

        assertEquals(listOf(DrainAction.Skip("skip-id"), DrainAction.Upload("upload-id")), actions)
    }

    @Test
    fun planDayDrainKeepsKeyPairingForNonCollidingRows() {
        val segments = listOf(
            segment("skip-id", MAIN_STREAM, QueueState.SEALED, key = "120000_1"),
            segment("upload-id", MAIN_STREAM, QueueState.SEALED, key = "120500_1"),
        )

        val actions = planDayDrain(
            verdicts = listOf(
                ReconcileVerdict(SegmentKey(DAY, "120500_1"), needsUpload = true),
                ReconcileVerdict(SegmentKey(DAY, "120000_1"), needsUpload = false),
            ),
            segments = segments,
        )

        assertEquals(listOf(DrainAction.Skip("skip-id"), DrainAction.Upload("upload-id")), actions)
    }

    @Test
    fun allReconciledSegmentsPlanNoUploads() {
        val segments = listOf(
            segment("skip-a", MAIN_STREAM, QueueState.SEALED, key = "120000_1"),
            segment("skip-b", MAIN_STREAM, QueueState.SEALED, key = "120500_1"),
        )

        val actions = planDayDrain(
            verdicts = listOf(
                ReconcileVerdict(SegmentKey(DAY, "120000_1"), needsUpload = false),
                ReconcileVerdict(SegmentKey(DAY, "120500_1"), needsUpload = false),
            ),
            segments = segments,
        )

        assertEquals(2, actions.filterIsInstance<DrainAction.Skip>().size)
        assertTrue(actions.filterIsInstance<DrainAction.Upload>().isEmpty())
    }

    @Test
    fun planDayDrainDoesNotCollapseRowsSharingWireKey() {
        val wireKey = "011500_300"
        val suffixedLeaf = "${wireKey}__ws1793519100000"
        val bare = segment("$DAY/$MAIN_STREAM/$wireKey", MAIN_STREAM, QueueState.SEALED, key = wireKey)
        val suffixed = segment(
            "$DAY/$MAIN_STREAM/$suffixedLeaf",
            MAIN_STREAM,
            QueueState.SEALED,
            key = wireKey,
            dirSegment = suffixedLeaf,
        )

        val actions = planDayDrain(
            verdicts = listOf(
                ReconcileVerdict(SegmentKey(DAY, wireKey), needsUpload = false),
                ReconcileVerdict(SegmentKey(DAY, wireKey), needsUpload = true),
            ),
            segments = listOf(bare, suffixed),
        )

        assertEquals(listOf(DrainAction.Skip(bare.id), DrainAction.Upload(suffixed.id)), actions)
    }

    @Test
    fun readPayloadForUsesDirSegmentNotWireSegment() {
        val spoolDir = Files.createTempDirectory("solstone-work-spool").toFile()
        try {
            val wireKey = "011500_300"
            val dirSegment = "${wireKey}__ws1793519100000"
            val row = segment(
                "$DAY/$MAIN_STREAM/$dirSegment",
                MAIN_STREAM,
                QueueState.SEALED,
                key = wireKey,
                dirSegment = dirSegment,
            )
            val file = BundleFile("audio", "audio.m4a", "sha", 5, "audio/mp4", 1, 2)
            val segmentDir = File(File(File(spoolDir, DAY), MAIN_STREAM), dirSegment)
            segmentDir.mkdirs()
            File(segmentDir, file.name).writeBytes("bytes".toByteArray())

            assertEquals("bytes", readPayloadFor(spoolDir, row, file).decodeToString())
        } finally {
            spoolDir.deleteRecursively()
        }
    }

    private fun segment(
        id: String,
        stream: String,
        state: QueueState,
        day: String = DAY,
        key: String = id,
        dirSegment: String = key,
        attemptCount: Int = 0,
        lastAttemptAt: Long? = null,
        lastStatusCode: Int? = null,
    ): SegmentRow =
        SegmentRow(
            id = id,
            day = day,
            stream = stream,
            segment = key,
            dirSegment = dirSegment,
            state = state,
            byteSize = 10,
            sealedAt = 100,
            homeInstanceId = null,
            observerHandle = null,
            attemptCount = attemptCount,
            lastStatusCode = lastStatusCode,
            lastAttemptAt = lastAttemptAt,
        )

    private fun file(segmentId: String, sourceId: String, name: String, sha: String): SegmentFileRow =
        SegmentFileRow(
            segmentId = segmentId,
            sourceId = sourceId,
            name = name,
            sha256 = sha,
            byteSize = 5,
            mediaType = "application/octet-stream",
            captureStartEpochMs = 1,
            captureEndEpochMs = 2,
        )

    private companion object {
        const val DAY = "20260617"
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60L * MINUTE_MS
    }
}
