// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.QueueState
import app.solstone.core.queue.QueueEvent
import app.solstone.core.queue.transition
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow

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
    fun segmentsByState(state: QueueState): List<SegmentRow> = rows.values.filter { it.state == state }

    fun add(row: SegmentRow, fileRows: List<SegmentFileRow> = emptyList()) {
        rows[row.id] = row
        if (fileRows.isNotEmpty()) {
            mutableFiles[row.id] = fileRows
        }
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

    override fun segmentRow(id: String): SegmentRow? = rows[id]
}
