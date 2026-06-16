// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import app.solstone.core.model.QueueState
import app.solstone.core.queue.EvictionApplyResult
import app.solstone.core.queue.EvictionResult
import app.solstone.core.queue.QueueEvent
import app.solstone.core.queue.QueueStore
import app.solstone.core.queue.SourceDeleteResult

class RoomQueueStore(private val dao: SegmentDao) : QueueStore {
    constructor(database: SolstonePersistenceDatabase) : this(database.segmentDao())

    override fun advance(segmentId: String, event: QueueEvent): QueueState =
        dao.advanceState(segmentId, event)

    override fun applyEvictions(result: EvictionResult): EvictionApplyResult =
        dao.applyEvictions(result)

    override fun deleteSource(sourceId: String): SourceDeleteResult =
        dao.deleteSource(sourceId)
}
