// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE segment ADD COLUMN server_key TEXT")
        db.execSQL("ALTER TABLE segment ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE segment ADD COLUMN last_status_code INTEGER")
        db.execSQL("ALTER TABLE segment ADD COLUMN last_attempt_at INTEGER")
        db.execSQL("ALTER TABLE segment ADD COLUMN dedupe_checked_at INTEGER")
        db.execSQL("ALTER TABLE segment ADD COLUMN last_error TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE segment ADD COLUMN dir_segment TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE segment SET dir_segment = segment")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS segment_new (
                id TEXT NOT NULL,
                day TEXT NOT NULL,
                stream TEXT NOT NULL,
                segment TEXT NOT NULL,
                dir_segment TEXT NOT NULL DEFAULT '',
                state TEXT NOT NULL,
                byte_size INTEGER NOT NULL,
                sealed_at INTEGER NOT NULL,
                home_instance_id TEXT,
                observer_handle TEXT,
                attempt_count INTEGER NOT NULL,
                last_status_code INTEGER,
                last_attempt_at INTEGER,
                last_error TEXT,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO segment_new (
                id, day, stream, segment, dir_segment, state, byte_size, sealed_at,
                home_instance_id, observer_handle, attempt_count, last_status_code, last_attempt_at, last_error
            )
            SELECT
                id, day, stream, segment, dir_segment, state, byte_size, sealed_at,
                home_instance_id, observer_handle, attempt_count, last_status_code, last_attempt_at, last_error
            FROM segment
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE segment")
        db.execSQL("ALTER TABLE segment_new RENAME TO segment")
    }
}

fun openSolstonePersistenceDatabase(
    context: Context,
    name: String = "solstone-persistence.db",
): SolstonePersistenceDatabase =
    Room.databaseBuilder(context, SolstonePersistenceDatabase::class.java, name)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .build()
