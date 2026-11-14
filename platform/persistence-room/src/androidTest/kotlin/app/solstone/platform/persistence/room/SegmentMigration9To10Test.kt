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
class SegmentMigration9To10Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SolstonePersistenceDatabase::class.java,
    )

    @Test
    fun migratesFromVersion9To10KeepingRowsAndIndexingStreamAndState() {
        helper.createDatabase(TEST_DB, 9).apply {
            execSQL(
                "INSERT INTO segment (id, day, stream, segment, dir_segment, state, byte_size, sealed_at, attempt_count) " +
                    "VALUES ('20261109/audio/100000_300', '20261109', 'audio', '100000_300', '100000_300', 'EVICTED', 42, 1000, 2)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10)
        db.query("SELECT state, byte_size, attempt_count, local_copy_absent FROM segment WHERE id = '20261109/audio/100000_300'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("EVICTED", cursor.getString(0))
            assertEquals(42L, cursor.getLong(1))
            assertEquals(2, cursor.getInt(2))
            // Every existing evicted row is looked at once more, then marked if its directory is gone.
            assertEquals(0, cursor.getInt(3))
        }
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'segment'").use { cursor ->
            val names = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            assertTrue(names.toString(), "index_segment_stream_state" in names)
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "segment-migration-9-10"
    }
}
