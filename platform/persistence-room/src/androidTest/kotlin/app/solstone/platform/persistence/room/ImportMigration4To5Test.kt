// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportMigration4To5Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SolstonePersistenceDatabase::class.java,
    )

    @Test
    fun migrationPreservesExistingTablesAndCreatesImportTable() {
        helper.createDatabase(TEST_DB, 4).apply {
            insertV4Rows()
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        db.query(
            """
            SELECT id, day, stream, segment, dir_segment, state, byte_size, sealed_at,
                   home_instance_id, observer_handle, attempt_count, last_status_code, last_attempt_at, last_error
            FROM segment
            WHERE id = ?
            """.trimIndent(),
            arrayOf(SEGMENT_ID),
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(SEGMENT_ID, cursor.getString(0))
            assertEquals(DAY, cursor.getString(1))
            assertEquals("main", cursor.getString(2))
            assertEquals("120000_10", cursor.getString(3))
            assertEquals("120000_10", cursor.getString(4))
            assertEquals("SEALED", cursor.getString(5))
            assertEquals(10L, cursor.getLong(6))
            assertEquals(1000L, cursor.getLong(7))
            assertEquals("home-inst", cursor.getString(8))
            assertEquals("obs-handle", cursor.getString(9))
            assertEquals(1, cursor.getInt(10))
            assertEquals(200, cursor.getInt(11))
            assertEquals(500L, cursor.getLong(12))
            assertEquals("none", cursor.getString(13))
        }

        db.query("SELECT COUNT(*) FROM segment_file").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM event").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM sync_state").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }

        db.query("SELECT COUNT(*) FROM import_item").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        val columnNames = mutableListOf<String>()
        db.query("PRAGMA table_info(import_item)").use { cursor ->
            while (cursor.moveToNext()) {
                columnNames += cursor.getString(1)
            }
        }
        assertTrue(columnNames.contains("id"))
        assertTrue(columnNames.contains("payload_kind"))
        assertTrue(columnNames.contains("display_name"))
        assertTrue(columnNames.contains("mime"))
        assertTrue(columnNames.contains("byte_size"))
        assertTrue(columnNames.contains("state"))
        assertTrue(columnNames.contains("created_at"))
        assertTrue(columnNames.contains("attempt_count"))

        db.close()
    }

    private fun SupportSQLiteDatabase.insertV4Rows() {
        execSQL(
            """
            INSERT INTO segment (
                id, day, stream, segment, dir_segment, state, byte_size, sealed_at,
                home_instance_id, observer_handle, attempt_count, last_status_code, last_attempt_at, last_error
            ) VALUES (
                '$SEGMENT_ID', '$DAY', 'main', '120000_10', '120000_10', 'SEALED', 10, 1000,
                'home-inst', 'obs-handle', 1, 200, 500, 'none'
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

        execSQL(
            """
            INSERT INTO event (
                segment_id, kind, at_epoch_ms, detail
            ) VALUES (
                '$SEGMENT_ID', 'eviction', 1000, 'detail'
            )
            """.trimIndent(),
        )

        execSQL(
            """
            INSERT INTO sync_state (
                id, pending_count, last_success_at, last_failure_at
            ) VALUES (
                0, 1, 100, 200
            )
            """.trimIndent(),
        )
    }

    private companion object {
        const val TEST_DB = "import-migration-4-5"
        const val DAY = "20260617"
        const val SEGMENT_ID = "$DAY/main/120000_10"
    }
}
