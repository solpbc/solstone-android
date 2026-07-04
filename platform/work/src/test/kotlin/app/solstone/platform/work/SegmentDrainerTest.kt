// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.QueueState
import app.solstone.core.observer.IngestOutcome
import app.solstone.core.observer.ReconcileAuthException
import app.solstone.core.observer.ReconcileUnavailableException
import app.solstone.core.observer.ReconcileVerdict
import app.solstone.core.queue.QueueEvent
import app.solstone.core.queue.transition
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SegmentDrainerTest {
    @Test
    fun drainsSealedUploadAndPersistsCleanSuccess() {
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(file("a"))))

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = acceptedIngest("srv-a"),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
        )

        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
        assertTrue(report.cleanDrain)
        assertFalse(report.failedThisRun)
        assertEquals(QueueState.UPLOADED, store.row("a").state)
        assertEquals("srv-a", store.row("a").serverKey)
        assertEquals(1, store.row("a").attemptCount)
        assertEquals(0, store.syncState!!.pendingCount)
        assertEquals(NOW, store.syncState!!.lastSuccessAt)
    }

    @Test
    fun recoversUploadingViaMarkFailedThenRetryThenUpload() {
        val store = FakeDrainStore(segment("a", QueueState.UPLOADING), files = mapOf("a" to listOf(file("a"))))

        drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = acceptedIngest("srv-a"),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
        )

        assertEquals(
            listOf(QueueEvent.MARK_FAILED, QueueEvent.RETRY, QueueEvent.MARK_UPLOADED),
            store.eventsFor("a"),
        )
        assertEquals(QueueState.UPLOADED, store.row("a").state)
        assertEquals("srv-a", store.row("a").serverKey)
        assertEquals(1, store.row("a").attemptCount)
    }

    @Test
    fun allHardFailuresReturnRetryNoCleanNoStamp() {
        val store = FakeDrainStore(
            segment("a", sealedAt = 1),
            segment("b", sealedAt = 2),
            files = mapOf("a" to listOf(file("a")), "b" to listOf(file("b"))),
            syncState = SyncStateRow(pendingCount = 2, lastSuccessAt = 123, lastFailureAt = null),
        )

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = rejectedIngest(422),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
        )

        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertFalse(report.cleanDrain)
        assertTrue(report.failedThisRun)
        assertEquals(123, store.syncState!!.lastSuccessAt)
        assertEquals(
            8,
            nextRecentErrorCount(previous = 7, cleanDrain = report.cleanDrain, failedThisRun = report.failedThisRun),
        )
        assertEquals(QueueState.FAILED, store.row("a").state)
        assertEquals(QueueState.FAILED, store.row("b").state)
    }

    @Test
    fun emptyQueueReturnsSuccessAndClean() {
        val store = FakeDrainStore(syncState = SyncStateRow(pendingCount = 0, lastSuccessAt = null, lastFailureAt = null))

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = acceptedIngest("unused"),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
        )

        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
        assertTrue(report.cleanDrain)
        assertFalse(report.failedThisRun)
        assertEquals(NOW, store.syncState!!.lastSuccessAt)
    }

    @Test
    fun idleBackfillInBackoffReturnsSuccessButNotClean() {
        val store = FakeDrainStore(
            segment(
                "failed",
                QueueState.FAILED,
                attemptCount = 1,
                lastAttemptAt = NOW - 1_000,
                lastStatusCode = 500,
            ),
            files = mapOf("failed" to listOf(file("failed"))),
            syncState = SyncStateRow(pendingCount = 1, lastSuccessAt = 100, lastFailureAt = 50),
        )

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = acceptedIngest("unused"),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
        )

        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
        assertFalse(report.cleanDrain)
        assertFalse(report.failedThisRun)
        assertEquals(100, store.syncState!!.lastSuccessAt)
        assertEquals(1, store.syncState!!.pendingCount)
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun payloadMissingMarksSegmentFailedAndContinues() {
        val store = FakeDrainStore(
            segment("missing", sealedAt = 1),
            segment("ok", sealedAt = 2),
            files = mapOf("missing" to listOf(file("missing")), "ok" to listOf(file("ok"))),
        )

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = acceptedIngest("srv"),
            readPayload = { segment, _ ->
                if (segment.id == "missing") throw FileNotFoundException("missing")
                byteArrayOf(1)
            },
            now = { NOW },
            log = store::log,
        )

        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertTrue(report.failedThisRun)
        assertEquals(QueueState.FAILED, store.row("missing").state)
        assertEquals("payload missing", store.row("missing").lastError)
        assertEquals(QueueState.UPLOADED, store.row("ok").state)
        assertTrue(store.logs.any { it.contains("payload missing missing") })
    }

    @Test
    fun payloadPathViolationMarksFailedAndContinues() {
        val store = FakeDrainStore(
            segment("bad", sealedAt = 1),
            segment("ok", sealedAt = 2),
            files = mapOf("bad" to listOf(file("bad")), "ok" to listOf(file("ok"))),
        )

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = acceptedIngest("srv"),
            readPayload = { segment, _ ->
                if (segment.id == "bad") throw IllegalArgumentException("bad path")
                byteArrayOf(1)
            },
            now = { NOW },
            log = store::log,
        )

        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertTrue(report.failedThisRun)
        assertEquals(QueueState.FAILED, store.row("bad").state)
        assertEquals("payload unreadable", store.row("bad").lastError)
        assertEquals(QueueState.UPLOADED, store.row("ok").state)
        assertTrue(store.logs.any { it.contains("payload unreadable bad") })
    }

    @Test
    fun reconcileUnavailableLeavesDayDrainableAndRetries() {
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(file("a"))))

        val report = drainSegments(
            store = store,
            reconcile = { _, _ -> throw ReconcileUnavailableException(500) },
            ingest = acceptedIngest("unused"),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
        )

        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertTrue(report.failedThisRun)
        assertEquals(QueueState.SEALED, store.row("a").state)
        assertTrue(store.events.isEmpty())
        assertTrue(store.logs.any { it.contains("reconcile unavailable day=$DAY") })
    }

    @Test
    fun reconcileAuthHaltsWithFailure() {
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(file("a"))))

        val report = drainSegments(
            store = store,
            reconcile = { _, _ -> throw ReconcileAuthException(401) },
            ingest = acceptedIngest("unused"),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
        )

        assertEquals(SyncOutcome.FAILURE, report.workOutcome)
        assertFalse(report.cleanDrain)
        assertTrue(report.failedThisRun)
        assertEquals(QueueState.SEALED, store.row("a").state)
        assertTrue(store.logs.any { it.contains("reconcile auth halt day=$DAY") })
    }

    @Test
    fun capLimitsAttemptsAndLeavesRemainderDueRetrying() {
        val segments = (0 until 51).map { index -> segment("seg-$index", sealedAt = index.toLong()) }
        val store = FakeDrainStore(
            rows = segments,
            files = segments.associate { it.id to listOf(file(it.id)) },
        )
        var ingestCount = 0

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                ingestCount += 1
                manifest.files.forEach { fileBytes(it) }
                IngestOutcome.Accepted("srv-${manifest.key.segment}")
            },
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
        )

        assertEquals(50, ingestCount)
        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertFalse(report.cleanDrain)
        assertFalse(report.failedThisRun)
        assertEquals(1, store.syncState!!.pendingCount)
    }

    @Test
    fun logsClaimPayloadAndReconcileCatches() {
        val claimStore = FakeDrainStore(segment("claim"), files = mapOf("claim" to listOf(file("claim"))))
        claimStore.failAdvanceFor += "claim"
        drainSegments(claimStore, uploadAll, acceptedIngest("unused"), readBytes, { NOW }, claimStore::log)

        val payloadStore = FakeDrainStore(segment("payload"), files = mapOf("payload" to listOf(file("payload"))))
        drainSegments(
            payloadStore,
            uploadAll,
            acceptedIngest("unused"),
            { _, _ -> throw FileNotFoundException("missing") },
            { NOW },
            payloadStore::log,
        )

        val reconcileStore = FakeDrainStore(segment("reconcile"), files = mapOf("reconcile" to listOf(file("reconcile"))))
        drainSegments(
            reconcileStore,
            { _, _ -> throw ReconcileUnavailableException(500) },
            acceptedIngest("unused"),
            readBytes,
            { NOW },
            reconcileStore::log,
        )

        assertTrue(claimStore.logs.any { it.contains("claim failed claim") })
        assertTrue(payloadStore.logs.any { it.contains("payload missing payload") })
        assertTrue(reconcileStore.logs.any { it.contains("reconcile unavailable day=$DAY") })
    }

    private class FakeDrainStore(
        rows: List<SegmentRow> = emptyList(),
        private val files: Map<String, List<SegmentFileRow>> = emptyMap(),
        syncState: SyncStateRow? = null,
    ) : DrainStore {
        constructor(
            vararg rows: SegmentRow,
            files: Map<String, List<SegmentFileRow>> = emptyMap(),
            syncState: SyncStateRow? = null,
        ) : this(rows.toList(), files, syncState)

        private val rows = rows.associateBy { it.id }.toMutableMap()
        val events = mutableListOf<Pair<String, QueueEvent>>()
        val logs = mutableListOf<String>()
        val failAdvanceFor = mutableSetOf<String>()
        var syncState: SyncStateRow? = syncState
            private set

        fun row(id: String): SegmentRow = rows.getValue(id)

        fun eventsFor(id: String): List<QueueEvent> =
            events.filter { it.first == id }.map { it.second }

        fun log(message: String, throwable: Throwable?) {
            logs += message + (throwable?.let { ": ${it.javaClass.simpleName}" } ?: "")
        }

        override fun syncState(): SyncStateRow? = syncState

        override fun segmentsForDrain(): List<SegmentRow> =
            rows.values
                .filter { it.state == QueueState.SEALED || it.state == QueueState.UPLOADING || it.state == QueueState.FAILED }
                .sortedWith(compareBy<SegmentRow> { it.sealedAt }.thenBy { it.id })

        override fun filesBySegmentId(id: String): List<SegmentFileRow> = files[id].orEmpty()

        override fun recordDedupeChecked(id: String, at: Long): Int {
            rows[id] = rows.getValue(id).copy(dedupeCheckedAt = at)
            return 1
        }

        override fun advanceState(id: String, event: QueueEvent): QueueState {
            if (id in failAdvanceFor) {
                throw IllegalStateException("forced claim failure")
            }
            val current = rows.getValue(id)
            val next = transition(current.state, event)
            rows[id] = current.copy(state = next)
            events += id to event
            return next
        }

        override fun recordAttempt(id: String, attempts: Int, at: Long): Int {
            rows[id] = rows.getValue(id).copy(attemptCount = attempts, lastAttemptAt = at)
            return 1
        }

        override fun recordUploaded(id: String, serverKey: String?): Int {
            rows[id] = rows.getValue(id).copy(serverKey = serverKey, lastError = null)
            return 1
        }

        override fun recordFailure(id: String, code: Int?, error: String?): Int {
            rows[id] = rows.getValue(id).copy(lastStatusCode = code, lastError = error)
            return 1
        }

        override fun pendingCount(stream: String): Int =
            rows.values.count {
                it.stream == stream &&
                    (it.state == QueueState.SEALED || it.state == QueueState.UPLOADING || it.state == QueueState.FAILED)
            }

        override fun upsertSyncState(row: SyncStateRow) {
            syncState = row
        }
    }

    private companion object {
        const val DAY = "20260617"
        const val NOW = 1_000_000L

        val uploadAll: (List<BundleManifest>, String) -> List<ReconcileVerdict> = { manifests, _ ->
            manifests.map { ReconcileVerdict(it.key, needsUpload = true) }
        }

        val readBytes: (SegmentRow, BundleFile) -> ByteArray = { _, _ -> byteArrayOf(1) }

        fun acceptedIngest(serverKey: String): (BundleManifest, (BundleFile) -> ByteArray) -> IngestOutcome =
            { manifest, fileBytes ->
                manifest.files.forEach { fileBytes(it) }
                IngestOutcome.Accepted(serverKey)
            }

        fun rejectedIngest(status: Int): (BundleManifest, (BundleFile) -> ByteArray) -> IngestOutcome =
            { manifest, fileBytes ->
                manifest.files.forEach { fileBytes(it) }
                IngestOutcome.Rejected(status, "rejected")
            }

        fun segment(
            id: String,
            state: QueueState = QueueState.SEALED,
            sealedAt: Long = 1,
            attemptCount: Int = 0,
            lastAttemptAt: Long? = null,
            lastStatusCode: Int? = null,
        ): SegmentRow =
            SegmentRow(
                id = id,
                day = DAY,
                stream = MAIN_STREAM,
                segment = id,
                state = state,
                byteSize = 1,
                sealedAt = sealedAt,
                homeInstanceId = null,
                observerHandle = null,
                attemptCount = attemptCount,
                lastStatusCode = lastStatusCode,
                lastAttemptAt = lastAttemptAt,
            )

        fun file(segmentId: String): SegmentFileRow =
            SegmentFileRow(
                segmentId = segmentId,
                sourceId = "audio",
                name = "$segmentId.bin",
                sha256 = "sha-$segmentId",
                byteSize = 1,
                mediaType = "application/octet-stream",
                captureStartEpochMs = 1,
                captureEndEpochMs = 2,
            )

    }
}
