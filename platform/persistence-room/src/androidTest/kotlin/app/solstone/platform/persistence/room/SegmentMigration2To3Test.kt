// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SegmentMigration2To3Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SolstonePersistenceDatabase::class.java,
    )

    @Test
    fun migrationBackfillsDirSegmentFromWireSegment() {
        helper.createDatabase(TEST_DB, 2).apply {
            insertV2Rows()
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)
        db.query(
            """
            SELECT id, segment, dir_segment
            FROM segment
            WHERE id = ?
            """.trimIndent(),
            arrayOf(SEGMENT_ID),
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(SEGMENT_ID, cursor.getString(0))
            assertEquals(SEGMENT, cursor.getString(1))
            assertEquals(SEGMENT, cursor.getString(2))
        }
        db.close()
    }

    private fun SupportSQLiteDatabase.insertV2Rows() {
        execSQL(
            """
            INSERT INTO segment (
                id, day, stream, segment, state, byte_size, sealed_at, home_instance_id, observer_handle,
                server_key, attempt_count, last_status_code, last_attempt_at, dedupe_checked_at, last_error
            ) VALUES (
                '$SEGMENT_ID', '$DAY', 'observer', '$SEGMENT', 'SEALED', 10, 1000, NULL, NULL,
                NULL, 0, NULL, NULL, NULL, NULL
            )
            """.trimIndent(),
        )
    }

    private companion object {
        const val TEST_DB = "segment-migration-2-3"
        const val DAY = "20260617"
        const val SEGMENT = "011500_300"
        const val SEGMENT_ID = "$DAY/observer/$SEGMENT"
    }
}
