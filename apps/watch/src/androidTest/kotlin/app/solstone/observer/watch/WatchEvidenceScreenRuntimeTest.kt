// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.watch

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.model.QueueState
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchEvidenceScreenRuntimeTest {
    @Test
    fun roomSeededEvidenceRendersProvenanceAndExports() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        val db = openSolstonePersistenceDatabase(context)
        db.segmentDao().insertSegmentWithFiles(
            SegmentRow(
                id = "seg-1",
                day = "20260617",
                stream = MAIN_STREAM,
                segment = "120000_300",
                state = QueueState.SEALED,
                byteSize = 5,
                sealedAt = 10,
                homeInstanceId = null,
                observerHandle = null,
            ),
            listOf(
                SegmentFileRow(
                    segmentId = "seg-1",
                    sourceId = "audio",
                    name = "audio.m4a",
                    sha256 = "sha",
                    byteSize = 5,
                    mediaType = "audio/mp4",
                    captureStartEpochMs = 1,
                    captureEndEpochMs = 2,
                ),
            ),
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            val container = waitForContainer()
            val evidence = container.controller.listEvidence()
            assertEquals("audio.m4a", evidence.single { row -> row.id == "seg-1" }.files.single().name)
            val result = container.controller.exportSegment(evidence.single { row -> row.id == "seg-1" })
            assertEquals(1, result.copiedFileCount)
        }
        db.close()
    }

    private fun waitForContainer(): WatchAppContainer {
        repeat(50) {
            WatchHarnessRuntime.container?.let { return it }
            Thread.sleep(100)
        }
        assumeTrue("watch harness container was not created", false)
        error("unreachable")
    }
}
