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

fun openSolstonePersistenceDatabase(
    context: Context,
    name: String = "solstone-persistence.db",
): SolstonePersistenceDatabase =
    Room.databaseBuilder(context, SolstonePersistenceDatabase::class.java, name)
        .addMigrations(MIGRATION_1_2)
        .build()
