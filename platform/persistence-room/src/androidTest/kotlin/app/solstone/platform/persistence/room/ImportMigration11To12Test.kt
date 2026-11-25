// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportMigration11To12Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SolstonePersistenceDatabase::class.java,
    )

    @Test
    fun migratesFromVersion11To12PreservingExistingRowsAndAddingResumableUploadColumns() {
        helper.createDatabase(TEST_DB, 11).apply {
            execSQL(
                """
                INSERT INTO import_item (
                    id, payload_kind, display_name, mime, byte_size, state, created_at,
                    progress_path, progress_timestamp, progress_instance_id,
                    backoff_instance_id, attempt_count, next_attempt_at,
                    attention_error, attention_reason_code, attention_kind,
                    attention_instance_id, attention_journal_version,
                    review_import_id, review_reason_code, review_kind, review_instance_id, review_expired
                ) VALUES (
                    'item-v11', 'file', 'archive.zip', 'application/zip', 33554432, 'pending', 5000,
                    NULL, NULL, NULL,
                    NULL, 0, NULL,
                    NULL, NULL, NULL,
                    NULL, NULL,
                    NULL, NULL, NULL, NULL, 0
                )
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, MIGRATION_11_12)

        db.query(
            """
            SELECT display_name, byte_size, payload_sha256, upload_id, upload_instance_id, upload_offset, upload_resumable
            FROM import_item
            WHERE id = 'item-v11'
            """.trimIndent(),
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("archive.zip", cursor.getString(0))
            assertEquals(33_554_432L, cursor.getLong(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertEquals(0L, cursor.getLong(5))
            assertEquals(0, cursor.getInt(6))
        }

        db.execSQL(
            """
            UPDATE import_item
            SET payload_sha256 = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                upload_id = '550e8400-e29b-41d4-a716-446655440000',
                upload_instance_id = 'inst-1',
                upload_offset = 4194304,
                upload_resumable = 1
            WHERE id = 'item-v11'
            """.trimIndent(),
        )

        db.query(
            "SELECT payload_sha256, upload_id, upload_instance_id, upload_offset, upload_resumable FROM import_item WHERE id = 'item-v11'",
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("a".repeat(64), cursor.getString(0))
            assertEquals("550e8400-e29b-41d4-a716-446655440000", cursor.getString(1))
            assertEquals("inst-1", cursor.getString(2))
            assertEquals(4_194_304L, cursor.getLong(3))
            assertEquals(1, cursor.getInt(4))
        }

        db.close()
    }

    private companion object {
        const val TEST_DB = "import-migration-11-12"
    }
}
