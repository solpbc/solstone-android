// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SegmentMigration1To2Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SolstonePersistenceDatabase::class.java,
    )

    @Test
    fun migrationPreservesSegmentAndAddsUploadDefaults() {
        helper.createDatabase(TEST_DB, 1).apply {
            insertV1Rows()
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)
        db.query(
            """
            SELECT state, server_key, attempt_count, last_status_code, last_attempt_at, dedupe_checked_at, last_error
            FROM segment
            WHERE id = ?
            """.trimIndent(),
            arrayOf(SEGMENT_ID),
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("SEALED", cursor.getString(0))
            assertNull(cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertNull(cursor.getString(3))
            assertNull(cursor.getString(4))
            assertNull(cursor.getString(5))
            assertNull(cursor.getString(6))
        }
        db.close()
    }

    private fun SupportSQLiteDatabase.insertV1Rows() {
        execSQL(
            """
            INSERT INTO segment (
                id, day, stream, segment, state, byte_size, sealed_at, home_instance_id, observer_handle
            ) VALUES (
                '$SEGMENT_ID', '$DAY', 'observer', '120000_10', 'SEALED', 10, 1000, NULL, NULL
            )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO segment_file (
                segment_id, source_id, name, sha256, byte_size, media_type, capture_start_epoch_ms, capture_end_epoch_ms
            ) VALUES (
                '$SEGMENT_ID', 'audio', 'audio.bin', 'sha-audio', 10, 'application/octet-stream', 1, 2
            )
            """.trimIndent(),
        )
    }

    private companion object {
        const val TEST_DB = "segment-migration-1-2"
        const val DAY = "20260617"
        const val SEGMENT_ID = "$DAY/observer/120000_10"
    }
}
