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
class ImportMigration10To11Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SolstonePersistenceDatabase::class.java,
    )

    @Test
    fun migratesFromVersion10To11PreservingCustodyRowsAndAddingReviewAndReceiptSupport() {
        helper.createDatabase(TEST_DB, 10).apply {
            execSQL(
                """
                INSERT INTO import_item (
                    id, payload_kind, display_name, mime, byte_size, state, created_at,
                    progress_path, progress_timestamp, progress_instance_id,
                    backoff_instance_id, attempt_count, next_attempt_at,
                    attention_error, attention_reason_code, attention_kind,
                    attention_instance_id, attention_journal_version
                ) VALUES (
                    'item-existing', 'file', 'export.zip', 'application/zip', 4096, 'pending', 1000,
                    '/imports/20261122_110000/export.zip', '20261122_110000', 'inst-a',
                    NULL, 0, NULL,
                    NULL, NULL, NULL,
                    NULL, NULL
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO import_delivery (id, last_delivered_at, instance_id) VALUES (0, 2000, 'inst-a')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 11, true, MIGRATION_10_11)

        db.query(
            """
            SELECT display_name, state, byte_size, progress_timestamp,
                   review_import_id, review_reason_code, review_kind, review_instance_id, review_expired
            FROM import_item
            WHERE id = 'item-existing'
            """.trimIndent(),
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("export.zip", cursor.getString(0))
            assertEquals("pending", cursor.getString(1))
            assertEquals(4096L, cursor.getLong(2))
            assertEquals("20261122_110000", cursor.getString(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.isNull(6))
            assertTrue(cursor.isNull(7))
            assertEquals(0, cursor.getInt(8))
        }

        db.execSQL(
            """
            UPDATE import_item
            SET state = 'ready_to_review',
                review_import_id = '20261122_110000',
                review_reason_code = 'import_preview_required',
                review_kind = 'body_confirmation_required',
                review_instance_id = 'inst-a',
                review_expired = 0
            WHERE id = 'item-existing'
            """.trimIndent(),
        )

        db.execSQL(
            """
            INSERT INTO import_recent_receipt (id, filename, import_id, instance_id, delivered_at)
            VALUES ('rcpt-1', 'notes.pdf', '20261122_110500', 'inst-a', 3000)
            """.trimIndent(),
        )

        db.query("SELECT filename, import_id, instance_id, delivered_at FROM import_recent_receipt WHERE id = 'rcpt-1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("notes.pdf", cursor.getString(0))
            assertEquals("20261122_110500", cursor.getString(1))
            assertEquals("inst-a", cursor.getString(2))
            assertEquals(3000L, cursor.getLong(3))
        }

        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'import_recent_receipt'").use { cursor ->
            val names = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            assertTrue(names.toString(), "index_import_recent_receipt_instance_id_delivered_at" in names)
        }

        db.close()
    }

    private companion object {
        const val TEST_DB = "import-migration-10-11"
    }
}
