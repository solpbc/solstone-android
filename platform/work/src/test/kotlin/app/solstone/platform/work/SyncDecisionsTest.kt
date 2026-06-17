// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.observer.IngestOutcome
import app.solstone.core.observer.ReconcileVerdict
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncDecisionsTest {
    @Test
    fun selectDrainSegmentsDrainsObserverSealedIncludingLocationAndExcludesOtherStreams() {
        val rows = listOf(
            segment("main-sealed", MAIN_STREAM, QueueState.SEALED),
            segment("observer-location", MAIN_STREAM, QueueState.SEALED),
            segment("import-sealed", "import.share", QueueState.SEALED),
            segment("main-uploading", MAIN_STREAM, QueueState.UPLOADING),
            segment("main-failed", MAIN_STREAM, QueueState.FAILED),
        )

        assertEquals(listOf("main-sealed", "observer-location"), selectDrainSegments(rows).map { it.id })
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
    fun ingestOutcomesMapToSyncResults() {
        assertEquals(SegmentSyncResult.Uploaded("seg1"), resolveIngestOutcome(IngestOutcome.Accepted("seg1")))
        assertEquals(SegmentSyncResult.Uploaded("seg2"), resolveIngestOutcome(IngestOutcome.Collision("seg2")))
        assertEquals(SegmentSyncResult.Uploaded("seg3"), resolveIngestOutcome(IngestOutcome.Duplicate("seg3")))
        assertEquals(SegmentSyncResult.Uploaded(null), resolveIngestOutcome(IngestOutcome.Duplicate(null)))
        assertEquals(SegmentSyncResult.AuthHalt(401), resolveIngestOutcome(IngestOutcome.Rejected(401, "")))
        assertEquals(SegmentSyncResult.AuthHalt(403), resolveIngestOutcome(IngestOutcome.Rejected(403, "")))
        assertEquals(SegmentSyncResult.Retry(500), resolveIngestOutcome(IngestOutcome.Rejected(500, "")))
        assertEquals(SegmentSyncResult.HardFail(404), resolveIngestOutcome(IngestOutcome.Rejected(404, "")))
        assertEquals(SegmentSyncResult.HardFail(400), resolveIngestOutcome(IngestOutcome.Rejected(400, "")))
        assertEquals(SegmentSyncResult.Retry(null), resolveIoError())
    }

    @Test
    fun haltsDrainOnlyForAuthHalt() {
        assertTrue(haltsDrain(SegmentSyncResult.AuthHalt(401)))
        assertFalse(haltsDrain(SegmentSyncResult.Uploaded("seg")))
        assertFalse(haltsDrain(SegmentSyncResult.Retry(500)))
        assertFalse(haltsDrain(SegmentSyncResult.HardFail(400)))
    }

    @Test
    fun nonAuthFailuresDoNotHaltLaterSegments() {
        val results = listOf(
            SegmentSyncResult.Uploaded("a"),
            SegmentSyncResult.Retry(500),
            SegmentSyncResult.HardFail(400),
            SegmentSyncResult.Uploaded("b"),
        )

        assertEquals(listOf(false, false, false, false), results.map(::haltsDrain))
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
    fun fakePlHttpClientRecordsRequestsForDecisionSeams() {
        val client = FakePlHttpClient(HttpResponse(200, emptyMap(), ByteArray(0)))

        client.request("GET", "/app/link/api/status", emptyMap(), null)

        assertEquals(listOf("/app/link/api/status"), client.paths)
        assertEquals(0, client.ingestCount)
    }

    private fun segment(
        id: String,
        stream: String,
        state: QueueState,
        day: String = DAY,
        key: String = id,
    ): SegmentRow =
        SegmentRow(
            id = id,
            day = day,
            stream = stream,
            segment = key,
            state = state,
            byteSize = 10,
            sealedAt = 100,
            homeInstanceId = null,
            observerHandle = null,
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

    private class FakePlHttpClient(vararg responses: HttpResponse) : PlHttpClient {
        private val scripted = ArrayDeque(responses.toList())
        val paths = mutableListOf<String>()
        var ingestCount = 0
            private set

        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            paths += path
            if (method == "POST") {
                ingestCount += 1
            }
            return scripted.removeFirst()
        }
    }

    private companion object {
        const val DAY = "20260617"
    }
}
