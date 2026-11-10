// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchReceiptMigration8To9Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SolstonePersistenceDatabase::class.java,
    )

    @Test
    fun migratesFromVersion8To9AndCreatesWatchBundleReceiptTable() {
        helper.createDatabase(TEST_DB, 8).apply {
            execSQL("INSERT INTO source_intake (source_id, total, started_at, last_at) VALUES ('audio', 23, 1000, 2000)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)
        db.execSQL(
            "INSERT INTO watch_bundle_receipt (bundle_id, sha256, seal_generation, segment_id, committed_at) " +
                "VALUES ('b-1', 'sha-1', 1, '20261109/watch/100000_300', 1000)",
        )
        db.query("SELECT bundle_id, sha256, seal_generation, segment_id, committed_at FROM watch_bundle_receipt").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("b-1", cursor.getString(0))
            assertEquals("sha-1", cursor.getString(1))
            assertEquals(1L, cursor.getLong(2))
            assertEquals("20261109/watch/100000_300", cursor.getString(3))
            assertEquals(1000L, cursor.getLong(4))
        }
        db.query("SELECT total, started_at, last_at FROM source_intake WHERE source_id = 'audio'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(23L, cursor.getLong(0))
            assertEquals(1000L, cursor.getLong(1))
            assertEquals(2000L, cursor.getLong(2))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "watch-receipt-migration-8-9"
    }
}
