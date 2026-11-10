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
class IntakeMigration7To8Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SolstonePersistenceDatabase::class.java,
    )

    @Test
    fun anExistingAudioFileDoesNotBecomeIntake() {
        helper.createDatabase(TEST_DB, 7).apply {
            execSQL(
                "INSERT INTO segment (id, day, stream, segment, dir_segment, state, byte_size, sealed_at, attempt_count) " +
                    "VALUES ('20261101/main/000000_000', '20261101', 'main', '000000_000', '000000_000', 'SEALED', 10, 1, 0)",
            )
            execSQL(
                "INSERT INTO segment_file (segment_id, source_id, name, sha256, byte_size, media_type, " +
                    "capture_start_epoch_ms, capture_end_epoch_ms) " +
                    "VALUES ('20261101/main/000000_000', 'audio', 'audio.m4a', 'abc', 10, 'audio/mp4', 0, 60000)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)
        db.query("SELECT COUNT(*) FROM source_intake").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM segment_file").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "intake-migration-7-8"
    }
}
