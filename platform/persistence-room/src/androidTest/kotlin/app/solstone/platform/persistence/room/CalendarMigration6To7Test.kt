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
class CalendarMigration6To7Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SolstonePersistenceDatabase::class.java,
    )

    @Test
    fun aSentRecordKeepsItsLastKnownCalendarAndHasNoAccount() {
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(
                "INSERT INTO calendar_identity (event, occurrence, published_fingerprint, last_title, last_calendar, " +
                    "holders, miss_count, provider_event_ids) VALUES ('e', '', 'f', 'design review', 'Work', '1', 0, '')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)
        db.query("SELECT last_title, last_calendar, last_account FROM calendar_identity").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("design review", cursor.getString(0))
            assertEquals("Work", cursor.getString(1))
            assertTrue(cursor.isNull(2))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "calendar-migration-6-7"
    }
}
