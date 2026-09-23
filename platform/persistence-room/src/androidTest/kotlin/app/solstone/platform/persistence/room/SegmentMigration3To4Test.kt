// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solstone.core.model.QueueState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SegmentMigration3To4Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SolstonePersistenceDatabase::class.java,
    )

    @Test
    fun migrationPreservesAllStatesAndDropsServerKeyAndDedupeCheckedAt() {
        helper.createDatabase(TEST_DB, 3).apply {
            insertV3Rows()
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        for (state in QueueState.entries) {
            val nullRowId = "$DAY/observer/${state.name.lowercase()}-null"
            db.query(
                """
                SELECT id, day, stream, segment, dir_segment, state, byte_size, sealed_at,
                       home_instance_id, observer_handle, attempt_count, last_status_code, last_attempt_at, last_error
                FROM segment
                WHERE id = ?
                """.trimIndent(),
                arrayOf(nullRowId),
            ).use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals(nullRowId, cursor.getString(0))
                assertEquals(DAY, cursor.getString(1))
                assertEquals("observer", cursor.getString(2))
                assertEquals("${state.name.lowercase()}-null", cursor.getString(3))
                assertEquals("${state.name.lowercase()}-null", cursor.getString(4))
                assertEquals(state.name, cursor.getString(5))
                assertEquals(10L, cursor.getLong(6))
                assertEquals(1000L, cursor.getLong(7))
                assertNull(cursor.getString(8))
                assertNull(cursor.getString(9))
                assertEquals(0, cursor.getInt(10))
                assertNull(cursor.getString(11))
                assertNull(cursor.getString(12))
                assertNull(cursor.getString(13))
            }

            val setRowId = "$DAY/observer/${state.name.lowercase()}-set"
            db.query(
                """
                SELECT id, day, stream, segment, dir_segment, state, byte_size, sealed_at,
                       home_instance_id, observer_handle, attempt_count, last_status_code, last_attempt_at, last_error
                FROM segment
                WHERE id = ?
                """.trimIndent(),
                arrayOf(setRowId),
            ).use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals(setRowId, cursor.getString(0))
                assertEquals(DAY, cursor.getString(1))
                assertEquals("observer", cursor.getString(2))
                assertEquals("${state.name.lowercase()}-set", cursor.getString(3))
                assertEquals("${state.name.lowercase()}-set", cursor.getString(4))
                assertEquals(state.name, cursor.getString(5))
                assertEquals(20L, cursor.getLong(6))
                assertEquals(2000L, cursor.getLong(7))
                assertEquals("home-inst", cursor.getString(8))
                assertEquals("obs-handle", cursor.getString(9))
                assertEquals(2, cursor.getInt(10))
                assertEquals(500, cursor.getInt(11))
                assertEquals(999L, cursor.getLong(12))
                assertEquals("err", cursor.getString(13))
            }
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

        val columnNames = mutableListOf<String>()
        db.query("PRAGMA table_info(segment)").use { cursor ->
            while (cursor.moveToNext()) {
                columnNames += cursor.getString(1)
            }
        }
        assertFalse(columnNames.contains("server_key"))
        assertFalse(columnNames.contains("dedupe_checked_at"))

        db.close()
    }

    @Test
    fun migrationChainFromV1ToV4PreservesRowAndDropsColumns() {
        helper.createDatabase(TEST_CHAIN_DB, 1).apply {
            execSQL(
                """
                INSERT INTO segment (
                    id, day, stream, segment, state, byte_size, sealed_at, home_instance_id, observer_handle
                ) VALUES (
                    '$SEGMENT_ID', '$DAY', 'observer', '120000_10', 'SEALED', 10, 1000, NULL, NULL
                )
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_CHAIN_DB, 4, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        db.query(
            """
            SELECT id, day, stream, segment, dir_segment, state
            FROM segment
            WHERE id = ?
            """.trimIndent(),
            arrayOf(SEGMENT_ID),
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(SEGMENT_ID, cursor.getString(0))
            assertEquals(DAY, cursor.getString(1))
            assertEquals("observer", cursor.getString(2))
            assertEquals("120000_10", cursor.getString(3))
            assertEquals("120000_10", cursor.getString(4))
            assertEquals("SEALED", cursor.getString(5))
        }

        val columnNames = mutableListOf<String>()
        db.query("PRAGMA table_info(segment)").use { cursor ->
            while (cursor.moveToNext()) {
                columnNames += cursor.getString(1)
            }
        }
        assertFalse(columnNames.contains("server_key"))
        assertFalse(columnNames.contains("dedupe_checked_at"))

        db.close()
    }

    private fun SupportSQLiteDatabase.insertV3Rows() {
        for (state in QueueState.entries) {
            val nullLeaf = "${state.name.lowercase()}-null"
            val nullId = "$DAY/observer/$nullLeaf"
            execSQL(
                """
                INSERT INTO segment (
                    id, day, stream, segment, dir_segment, state, byte_size, sealed_at, home_instance_id, observer_handle,
                    server_key, attempt_count, last_status_code, last_attempt_at, dedupe_checked_at, last_error
                ) VALUES (
                    '$nullId', '$DAY', 'observer', '$nullLeaf', '$nullLeaf', '${state.name}', 10, 1000, NULL, NULL,
                    NULL, 0, NULL, NULL, NULL, NULL
                )
                """.trimIndent(),
            )

            val setLeaf = "${state.name.lowercase()}-set"
            val setId = "$DAY/observer/$setLeaf"
            execSQL(
                """
                INSERT INTO segment (
                    id, day, stream, segment, dir_segment, state, byte_size, sealed_at, home_instance_id, observer_handle,
                    server_key, attempt_count, last_status_code, last_attempt_at, dedupe_checked_at, last_error
                ) VALUES (
                    '$setId', '$DAY', 'observer', '$setLeaf', '$setLeaf', '${state.name}', 20, 2000, 'home-inst', 'obs-handle',
                    'srv-key-1', 2, 500, 999, 888, 'err'
                )
                """.trimIndent(),
            )
        }

        execSQL(
            """
            INSERT INTO segment_file (
                segment_id, source_id, name, sha256, byte_size, media_type, capture_start_epoch_ms, capture_end_epoch_ms
            ) VALUES (
                '$DAY/observer/sealed-null', 'audio', 'audio.bin', 'sha-audio', 10, 'application/octet-stream', 1, 2
            )
            """.trimIndent(),
        )

        execSQL(
            """
            INSERT INTO event (
                segment_id, kind, at_epoch_ms, detail
            ) VALUES (
                '$DAY/observer/sealed-null', 'eviction', 1000, 'detail'
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
        const val TEST_DB = "segment-migration-3-4"
        const val TEST_CHAIN_DB = "segment-migration-1-4"
        const val DAY = "20260617"
        const val SEGMENT_ID = "$DAY/observer/120000_10"
    }
}
