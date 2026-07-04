// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import app.solstone.core.model.QueueState

@Database(
    entities = [
        SegmentRow::class,
        SegmentFileRow::class,
        EventRow::class,
        SyncStateRow::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(QueueStateConverters::class)
abstract class SolstonePersistenceDatabase : RoomDatabase() {
    abstract fun segmentDao(): SegmentDao
}

class QueueStateConverters {
    @TypeConverter
    fun fromQueueState(state: QueueState): String = state.name

    @TypeConverter
    fun toQueueState(value: String): QueueState = QueueState.valueOf(value)
}
