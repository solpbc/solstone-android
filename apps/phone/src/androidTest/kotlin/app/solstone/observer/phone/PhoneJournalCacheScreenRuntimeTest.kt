// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.observer.harness.decimalBytes
import app.solstone.observer.harness.HarnessJournalCacheBlockedReason
import app.solstone.observer.harness.HarnessJournalCachePass
import app.solstone.observer.harness.HarnessJournalCacheSaveError
import app.solstone.observer.harness.HarnessJournalCacheState
import app.solstone.observer.formfactor.shared.ObserverHarnessUi
import app.solstone.observer.formfactor.shared.QrBackend
import app.solstone.observer.scaffold.ObserverActivity
import app.solstone.observer.scaffold.ObserverHarnessRuntime
import app.solstone.observer.scaffold.ObserverRuntimeHooks
import app.solstone.platform.persistence.room.JOURNAL_CACHE_LIMIT_CHOICES_BYTES
import app.solstone.platform.persistence.room.JournalCacheLimitStore
import app.solstone.platform.power.GuidanceAction
import app.solstone.platform.power.GuidanceLaunchResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneJournalCacheScreenRuntimeTest {
    private val context: android.content.Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
        context.filesDir.resolve("journal-cache-limit").delete()
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
    }

    @Test
    fun localCacheLoadsOffMainAndPersistsAChoiceAcrossUiRecreation() {
        var loaded = CountDownLatch(1)
        ObserverHarnessRuntime.hooks = ObserverRuntimeHooks().also {
            it.onJournalCacheLoadComplete = { loaded.countDown() }
        }

        ActivityScenario.launch(ObserverActivity::class.java).use { scenario ->
            val container = waitForObserverContainer()
            assertTrue(waitForRecovery(container))
            waitUntil("initial local cache pass") { container.journalCacheState().latestPass != null }
            scenario.onActivity { activity ->
                clickButton(activity.findViewById(android.R.id.content), "Local cache")
                assertEquals("filesystem/Room load must not finish inline on main", 1L, loaded.count)
            }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity -> assertCompleteScreen(collectTexts(activity.findViewById(android.R.id.content))) }

            val selected = JOURNAL_CACHE_LIMIT_CHOICES_BYTES.first { it != 4_000_000_000L }
            loaded = CountDownLatch(1)
            ObserverHarnessRuntime.hooks?.onJournalCacheLoadComplete = { loaded.countDown() }
            scenario.onActivity { activity ->
                clickButton(activity.findViewById(android.R.id.content), "Use ${decimalBytes(selected)}")
                assertEquals("durable save must not finish inline on main", 1L, loaded.count)
            }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            assertEquals(selected, JournalCacheLimitStore(context.filesDir.resolve("journal-cache-limit")).snapshot().configuredLimitBytes)

            scenario.recreate()
            loaded = CountDownLatch(1)
            ObserverHarnessRuntime.hooks?.onJournalCacheLoadComplete = { loaded.countDown() }
            scenario.onActivity { activity -> clickButton(activity.findViewById(android.R.id.content), "Local cache") }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val texts = collectTexts(activity.findViewById(android.R.id.content))
                assertTrue(texts.any { it.contains("Current limit: ${decimalBytes(selected)}") })
            }
        }
    }

    @Test
    fun injectedSaveMeasurementPressureAndResidualFailuresRenderHonestly() {
        var loaded = CountDownLatch(1)
        var state = failureState(HarnessJournalCacheBlockedReason.MEASUREMENT_FAILED, usage = null)

        ActivityScenario.launch(ObserverActivity::class.java).use { scenario ->
            val container = waitForObserverContainer()
            assertTrue(waitForRecovery(container))
            scenario.onActivity { activity ->
                val ui = ObserverHarnessUi(
                    context = activity,
                    controller = container.controller,
                    permissionRequester = {},
                    asyncLoad = container.asyncLoad,
                    previewHeightPx = 100,
                    qrBackend = QrBackend.Camera2,
                    qrThreadLabel = "test",
                    batteryExemptionGranted = { true },
                    batteryGuidance = GuidanceAction("test", null, "Test settings"),
                    launchBatteryGuidance = { GuidanceLaunchResult.Failed("not used") },
                    journalCacheState = { state },
                    saveJournalCacheLimit = { state.copy(saveError = HarnessJournalCacheSaveError.FAILED) },
                    onJournalCacheLoadComplete = { loaded.countDown() },
                )
                activity.setContentView(ui.view())
                clickButton(activity.findViewById(android.R.id.content), "Local cache")
                assertEquals("injected load must remain asynchronous", 1L, loaded.count)
            }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                assertTrue(collectTexts(activity.findViewById(android.R.id.content)).any { it.contains("Attention: cache usage check failed") })
            }

            state = failureState(HarnessJournalCacheBlockedReason.NO_SAFE_ELIGIBLE_SEGMENT)
            loaded = CountDownLatch(1)
            scenario.onActivity { activity ->
                clickButton(activity.findViewById(android.R.id.content), "Back")
                clickButton(activity.findViewById(android.R.id.content), "Local cache")
            }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                assertTrue(collectTexts(activity.findViewById(android.R.id.content)).any { it.contains("no safely removable uploaded data") })
            }

            state = failureState(null, residuals = 1)
            loaded = CountDownLatch(1)
            scenario.onActivity { activity ->
                clickButton(activity.findViewById(android.R.id.content), "Back")
                clickButton(activity.findViewById(android.R.id.content), "Local cache")
            }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            loaded = CountDownLatch(1)
            scenario.onActivity { activity ->
                val texts = collectTexts(activity.findViewById(android.R.id.content))
                assertTrue(texts.any { it.contains("Removal retry pending: 1") })
                clickButton(activity.findViewById(android.R.id.content), "Use 1 GB")
            }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                assertTrue(collectTexts(activity.findViewById(android.R.id.content)).any { it.contains("Previous limit kept") })
            }
        }
    }

    private fun assertCompleteScreen(texts: List<String>) {
        assertTrue(texts.any { it.contains("Local cache") })
        assertTrue(texts.any { it.contains("Cache usage:") })
        assertTrue(texts.any { it.contains("Current limit: 4 GB") })
        JOURNAL_CACHE_LIMIT_CHOICES_BYTES.forEach { choice ->
            assertTrue(texts.any { it.contains("Use ${decimalBytes(choice)}") })
        }
    }

    private fun failureState(
        blocked: HarnessJournalCacheBlockedReason?,
        usage: Long? = 0L,
        residuals: Int = 0,
    ) = HarnessJournalCacheState(
        configuredLimitBytes = 4_000_000_000L,
        limitFallback = null,
        limitChoicesBytes = JOURNAL_CACHE_LIMIT_CHOICES_BYTES,
        latestPass = HarnessJournalCachePass(
            measuredUsageBytes = usage,
            measuredFreeBytes = 8_000_000_000L,
            configuredLimitBytes = 4_000_000_000L,
            pressureRemains = blocked != null,
            durablyMarkedCount = 0,
            reclaimedDirectoryCount = 0,
            reclaimedBytes = 0L,
            retryableResidualCount = residuals,
            refusedPathCount = 0,
            blockedReason = blocked,
        ),
        saveError = null,
    )

    private fun collectTexts(root: View): List<String> = buildList {
        fun visit(view: View) {
            if (view is TextView) add(view.text.toString())
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
    }

    private fun clickButton(root: View, label: String) {
        fun visit(view: View): Boolean {
            if (view is Button && view.text.toString() == label) return view.performClick().let { true }
            if (view is ViewGroup) for (i in 0 until view.childCount) if (visit(view.getChildAt(i))) return true
            return false
        }
        check(visit(root)) { "button not found: $label" }
    }
}
