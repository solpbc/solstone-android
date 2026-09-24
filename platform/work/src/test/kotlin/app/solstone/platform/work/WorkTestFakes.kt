// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.QueueState
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.queue.QueueEvent
import app.solstone.core.queue.transition
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.persistence.room.ConfirmedCopyFinisher
import app.solstone.platform.persistence.room.EventRow
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

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
    private val mutableFiles = files.toMutableMap()
    val events = mutableListOf<Pair<String, QueueEvent>>()
    val logs = mutableListOf<String>()
    val failAdvanceFor = mutableSetOf<String>()
    var syncState: SyncStateRow? = syncState
        private set

    fun row(id: String): SegmentRow = rows.getValue(id)
    fun allRows(): List<SegmentRow> = rows.values.sortedWith(compareBy<SegmentRow> { it.sealedAt }.thenBy { it.id })
    fun rowOrNull(id: String): SegmentRow? = rows[id]

    fun add(row: SegmentRow, fileRows: List<SegmentFileRow> = emptyList()) {
        rows[row.id] = row
        if (fileRows.isNotEmpty()) {
            mutableFiles[row.id] = fileRows
        }
    }

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

    override fun filesBySegmentId(id: String): List<SegmentFileRow> = mutableFiles[id].orEmpty()

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

    override fun recordUploaded(id: String): Int {
        rows[id] = rows.getValue(id).copy(lastError = null)
        return 1
    }

    override fun recordFailure(id: String, code: Int?, error: String?): Int {
        rows[id] = rows.getValue(id).copy(lastStatusCode = code, lastError = error)
        return 1
    }

    override fun pendingCount(stream: String): Int =
        rows.values.count {
            it.stream == stream &&
                (it.state == QueueState.SEALED || it.state == QueueState.UPLOADING || it.state == QueueState.FAILED) &&
                it.lastError != "removed_in_journal"
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
    lastError: String? = null,
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
        lastError = lastError,
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

    override fun request(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxResponseBytes: Int,
    ): HttpResponse {
        requests += RecordedRequest(method, path, headers, body)
        return scripted.removeFirst()
    }

    override fun close() {
        closed = true
    }
}

internal fun dummyFinisher(
    root: Path = Files.createTempDirectory("dummy-finisher"),
    dao: SegmentDao = dummyDao(),
): ConfirmedCopyFinisher = ConfirmedCopyFinisher(root, dao)

internal fun dummyDao(): SegmentDao = object : SegmentDao() {
    override fun insertSegment(segment: SegmentRow) = Unit
    override fun insertFiles(files: List<SegmentFileRow>) = Unit
    override fun insertEvents(events: List<EventRow>) = Unit
    override fun segmentsByState(state: QueueState): List<SegmentRow> = emptyList()
    override fun segmentsForDrain(stream: String): List<SegmentRow> = emptyList()
    override fun segmentsByDay(day: String): List<SegmentRow> = emptyList()
    override fun segmentById(id: String): SegmentRow? = null
    override fun duplicateBySha256(sha256: String): List<SegmentFileRow> = emptyList()
    override fun filesBySegmentId(segmentId: String): List<SegmentFileRow> = emptyList()
    override fun recordAttempt(id: String, attempts: Int, at: Long): Int = 0
    override fun recordUploaded(id: String): Int = 0
    override fun recordFailure(id: String, code: Int?, error: String?): Int = 0
    override fun upsertSyncState(row: SyncStateRow) = Unit
    override fun syncState(): SyncStateRow? = null
    override fun pendingCount(stream: String): Int = 0
    override fun pendingSourceIds(stream: String): List<String> = emptyList()
    override fun segmentState(id: String): QueueState? = null
    override fun updateState(id: String, state: QueueState): Int = 0
    override fun deleteFilesBySegmentId(segmentId: String): Int = 0
    override fun deleteFilesBySegmentIds(segmentIds: List<String>): Int = 0
    override fun deleteFilesBySource(sourceId: String): Int = 0
}
