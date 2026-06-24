// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.Manifest
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.solstone.core.model.QueueState
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassesCaptureRuntimeTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @After
    fun resetRuntime() {
        GlassesHarnessRuntime.hooks = null
        GlassesHarnessRuntime.container = null
    }

    @Test
    fun noLocationReadySchedulesPeriodicAndSealsAudioAndCamera() {
        clearPersistence()

        ActivityScenario.launch(MainActivity::class.java).use {
            val container = waitForContainer()
            assertTrue(container.controller.refreshPermissions().allRequiredGranted)
            assertEquals(1, container.flavor.syncControl?.enqueuePeriodicCalls)

            container.controller.start()
            Thread.sleep(250L)
            container.controller.stop()

            val files = waitForSingleSealedSegmentFiles()
            assertTrue(files.any { it.sourceId == "audio" && it.name == "audio.m4a" && it.mediaType == "audio/mp4" })
            assertTrue(
                files.any {
                    it.sourceId == "camera" &&
                        it.name.startsWith("camera-") &&
                        it.name.endsWith(".jpg") &&
                        it.mediaType == "image/jpeg"
                },
            )
        }
    }

    private fun waitForContainer(): GlassesAppContainer {
        return GlassesHarnessRuntime.container ?: run {
            assumeTrue("glasses harness container was not created", false)
            error("unreachable")
        }
    }

    private fun waitForSingleSealedSegmentFiles(): List<SegmentFileRow> {
        repeat(50) {
            val files = sealedSegmentFiles()
            if (files != null) return files
            Thread.sleep(100L)
        }
        error("sealed dual-source segment was not persisted")
    }

    private fun sealedSegmentFiles(): List<SegmentFileRow>? {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = openSolstonePersistenceDatabase(context)
        return try {
            val segments = db.segmentDao().segmentsByState(QueueState.SEALED)
            if (segments.size != 1) return null
            val files = db.segmentDao().filesBySegmentId(segments.single().id)
            if (files.any { it.sourceId == "audio" } && files.any { it.sourceId == "camera" }) files else null
        } finally {
            db.close()
        }
    }

    private fun clearPersistence() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("solstone-persistence.db")
        context.filesDir.resolve("spool").deleteRecursively()
    }

    private fun File.deleteRecursively() {
        if (!exists()) return
        walkBottomUp().forEach { it.delete() }
    }
}
