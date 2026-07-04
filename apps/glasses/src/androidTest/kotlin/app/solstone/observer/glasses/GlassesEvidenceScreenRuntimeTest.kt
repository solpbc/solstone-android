// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.Manifest
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.solstone.core.model.QueueState
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassesEvidenceScreenRuntimeTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @After
    fun resetHooks() {
        GlassesHarnessRuntime.hooks = null
        // Evict the (possibly closed) container from the application-scoped runtime so the
        // next test's launch rebuilds a fresh one instead of inheriting a closed database.
        GlassesHarnessRuntime.runtime?.closeForTest()
    }

    @Test
    fun roomSeededEvidenceAndStatusRenderOnUiThread() {
        val evidenceLatch = CountDownLatch(1)
        val syncLatch = CountDownLatch(1)
        GlassesHarnessRuntime.hooks = GlassesRuntimeHooks().also { hooks ->
            hooks.onEvidenceLoadComplete = { evidenceLatch.countDown() }
            hooks.onSyncLoadComplete = { syncLatch.countDown() }
        }
        seedEvidence()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The container may predate this test's hooks (built at Application scope),
            // so await the durable recovery fact, not the hook.
            assertTrue(waitForRecovery(waitForContainer()))
            scenario.onActivity { activity ->
                clickButton(activity.findViewById(android.R.id.content), "Evidence + export")
            }
            assertTrue(evidenceLatch.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val texts = collectTexts(activity.findViewById(android.R.id.content))
                assertTrue(texts.any { it.contains("seg-1") || it.contains("120000_300") })
                assertTrue(texts.any { it.contains("audio.m4a") })
                assertFalse(texts.any { it.contains("No sealed segments") })
            }

            scenario.onActivity { activity ->
                clickButton(activity.findViewById(android.R.id.content), "Back")
            }
            scenario.onActivity { activity ->
                clickButton(activity.findViewById(android.R.id.content), "Status + queue/sync")
            }
            assertTrue(syncLatch.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val texts = collectTexts(activity.findViewById(android.R.id.content))
                assertTrue(texts.any { it.contains("Pending:") })
                assertTrue(texts.any { it.contains("Last success:") })
                assertTrue(texts.any { it.contains("Last failure:") })
            }
        }
    }

    @Test
    fun failedEvidenceReadRendersDistinctErrorState() {
        var evidenceLatch = CountDownLatch(1)
        GlassesHarnessRuntime.hooks = GlassesRuntimeHooks().also { hooks ->
            hooks.onEvidenceLoadComplete = { evidenceLatch.countDown() }
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The container may predate this test's hooks (built at Application scope),
            // so await the durable recovery fact, not the hook.
            assertTrue(waitForRecovery(waitForContainer()))
            scenario.onActivity {
                GlassesHarnessRuntime.container!!.close()
            }
            evidenceLatch = CountDownLatch(1)
            GlassesHarnessRuntime.hooks?.onEvidenceLoadComplete = { evidenceLatch.countDown() }
            scenario.onActivity { activity ->
                clickButton(activity.findViewById(android.R.id.content), "Evidence + export")
            }
            assertTrue(evidenceLatch.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val texts = collectTexts(activity.findViewById(android.R.id.content))
                assertTrue(texts.any { it.contains("Couldn't load evidence") })
                assertFalse(texts.any { it.contains("No sealed segments") })
            }
        }
    }

    private fun seedEvidence() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        val db = openSolstonePersistenceDatabase(context)
        try {
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
        } finally {
            db.close()
        }
    }

    private fun waitForContainer(): GlassesAppContainer {
        return GlassesHarnessRuntime.container as? GlassesAppContainer ?: run {
            assumeTrue("glasses harness container was not created", false)
            error("unreachable")
        }
    }

    private fun waitForRecovery(container: GlassesAppContainer): Boolean {
        repeat(100) {
            if (container.recoveryCompleted) return true
            Thread.sleep(100L)
        }
        return container.recoveryCompleted
    }

    private fun collectTexts(root: View): List<String> {
        val texts = mutableListOf<String>()
        fun visit(view: View) {
            if (view is TextView) texts += view.text.toString()
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    visit(view.getChildAt(i))
                }
            }
        }
        visit(root)
        return texts
    }

    private fun clickButton(root: View, label: String) {
        fun visit(view: View): Boolean {
            if (view is Button && view.text.toString() == label) {
                view.performClick()
                return true
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    if (visit(view.getChildAt(i))) return true
                }
            }
            return false
        }
        check(visit(root)) { "button not found: $label" }
    }
}
