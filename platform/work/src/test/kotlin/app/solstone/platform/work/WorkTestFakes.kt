// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.QueueState
import app.solstone.core.observer.REGISTER_PATH
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.queue.QueueEvent
import app.solstone.core.queue.transition
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow
import java.io.Closeable

internal const val WORK_TEST_DAY = "20260617"

internal class FakeDrainStore(
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

internal fun segment(
    id: String,
    state: QueueState = QueueState.SEALED,
    sealedAt: Long = 1,
    attemptCount: Int = 0,
    lastAttemptAt: Long? = null,
    lastStatusCode: Int? = null,
): SegmentRow =
    SegmentRow(
        id = id,
        day = WORK_TEST_DAY,
        stream = MAIN_STREAM,
        segment = id,
        dirSegment = id,
        state = state,
        byteSize = 1,
        sealedAt = sealedAt,
        homeInstanceId = null,
        observerHandle = null,
        attemptCount = attemptCount,
        lastStatusCode = lastStatusCode,
        lastAttemptAt = lastAttemptAt,
    )

internal fun file(segmentId: String): SegmentFileRow =
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

internal data class RecordedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
)

internal class RecordingPlHttpClient(vararg responses: HttpResponse) : PlHttpClient, Closeable {
    private val scripted = ArrayDeque(responses.toList())
    val requests = mutableListOf<RecordedRequest>()
    var closed = false
        private set

    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        if (path == REGISTER_PATH) {
            throw AssertionError("legacy registration request is forbidden")
        }
        requests += RecordedRequest(method, path, headers, body)
        return scripted.removeFirst()
    }

    override fun close() {
        closed = true
    }
}
