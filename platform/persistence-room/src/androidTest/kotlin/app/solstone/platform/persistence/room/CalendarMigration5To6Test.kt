// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarMigration5To6Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SolstonePersistenceDatabase::class.java,
    )

    @Test
    fun migrationKeepsSegmentsAndAddsCalendarTables() {
        helper.createDatabase(TEST_DB, 5).apply {
            insertSegment()
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)
        db.query("SELECT id, stream FROM segment").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(SEGMENT_ID, cursor.getString(0))
            assertEquals("main", cursor.getString(1))
        }
        listOf(
            "calendar_settings",
            "calendar_calendar",
            "calendar_count",
            "calendar_identity",
            "calendar_intent",
            "calendar_capability",
        ).forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
        db.close()
    }

    @Test
    fun aLedgerIntentSurvivesReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(REOPEN_DB)
        val row = CalendarIntentRow(
            id = "abc",
            day = "20261019",
            segment = "094000_300",
            body = """{"v":1,"event":"e"}""",
            sealed = false,
            identityKeys = "e\u0000",
        )
        open(context).use { db ->
            db.calendarDao().upsertIntents(listOf(row))
        }
        open(context).use { db ->
            val stored = db.calendarDao().intents().single()
            assertEquals(row.body, stored.body)
            assertEquals(row.day, stored.day)
            assertEquals(row.segment, stored.segment)
            assertFalse(stored.sealed)
            assertEquals(row.identityKeys, stored.identityKeys)
        }
    }

    private fun open(context: Context): SolstonePersistenceDatabase =
        Room.databaseBuilder(context, SolstonePersistenceDatabase::class.java, REOPEN_DB)
            .addMigrations(MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()

    private fun SupportSQLiteDatabase.insertSegment() {
        execSQL(
            """
            INSERT INTO segment (
                id, day, stream, segment, dir_segment, state, byte_size, sealed_at,
                home_instance_id, observer_handle, attempt_count, last_status_code, last_attempt_at, last_error
            ) VALUES (
                '$SEGMENT_ID', '20261019', 'main', '094000_300', '094000_300', 'SEALED', 10, 1000,
                NULL, NULL, 0, NULL, NULL, NULL
            )
            """.trimIndent(),
        )
    }

    private companion object {
        const val TEST_DB = "calendar-migration-5-6"
        const val REOPEN_DB = "calendar-ledger-reopen"
        const val SEGMENT_ID = "20261019/main/094000_300"
    }
}
