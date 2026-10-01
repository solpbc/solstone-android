// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.observer.INGEST_PATH
import app.solstone.core.observer.SEGMENTS_PATH
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassesProcessDeathSendTest {
    @Test
    fun processDeathAfterDurableCommitRecoversAndSyncSends() {
        val startup = GlassesRealTestRunner.startupArg
        assumeTrue("startup arg must be death", startup == "death")
        val standIn = GlassesRealTestRunner.standIn
        requireNotNull(standIn)

        val context: Context = ApplicationProvider.getApplicationContext()
        val db = openSolstonePersistenceDatabase(context)

        waitForGlassesSegmentsEvicted(db)

        assertTrue(standIn.recordedRequests.any { it.method == "GET" && it.path.startsWith(SEGMENTS_PATH) })
        assertTrue(standIn.recordedRequests.any { it.method == "POST" && it.path == INGEST_PATH })
    }
}
