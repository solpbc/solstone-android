// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.watch

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.observer.harness.HarnessJournalCachePass
import app.solstone.observer.harness.decimalBytes
import app.solstone.observer.scaffold.ObserverActivity
import app.solstone.observer.scaffold.ObserverAppContainer
import app.solstone.observer.scaffold.ObserverHarnessRuntime
import app.solstone.observer.scaffold.ObserverRuntimeHooks
import app.solstone.platform.persistence.room.DEFAULT_JOURNAL_CACHE_LIMIT_BYTES
import app.solstone.platform.persistence.room.JOURNAL_CACHE_LIMIT_CHOICES_BYTES
import app.solstone.platform.persistence.room.JournalCacheLimitStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchJournalCacheScreenRuntimeTest {
    private val context: android.content.Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
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
            assertEquals(
                "initial pass must run under the default limit, so a later pass under the chosen limit can only come from the save",
                DEFAULT_JOURNAL_CACHE_LIMIT_BYTES,
                waitForInitialPass(container).configuredLimitBytes,
            )
            scenario.onActivity { activity ->
                clickButton(activity.findViewById(android.R.id.content), "Local cache")
                assertEquals("filesystem/Room load must not finish inline on main", 1L, loaded.count)
            }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity -> assertCompleteScreen(collectTexts(activity.findViewById(android.R.id.content))) }

            val selected = JOURNAL_CACHE_LIMIT_CHOICES_BYTES.first { it != DEFAULT_JOURNAL_CACHE_LIMIT_BYTES }
            loaded = CountDownLatch(1)
            ObserverHarnessRuntime.hooks?.onJournalCacheLoadComplete = { loaded.countDown() }
            scenario.onActivity { activity ->
                clickButton(activity.findViewById(android.R.id.content), "Use ${decimalBytes(selected)}")
                assertEquals("durable save must not finish inline on main", 1L, loaded.count)
            }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            assertEquals(selected, JournalCacheLimitStore(context.filesDir.resolve("journal-cache-limit")).snapshot().configuredLimitBytes)
            waitForPassUnderLimit(container, selected)

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

    private fun assertCompleteScreen(texts: List<String>) {
        assertTrue(texts.any { it.contains("Local cache") })
        assertTrue(texts.any { it.contains("Cache usage:") })
        assertTrue(texts.any { it.contains("Current limit: ${decimalBytes(DEFAULT_JOURNAL_CACHE_LIMIT_BYTES)}") })
        JOURNAL_CACHE_LIMIT_CHOICES_BYTES.forEach { choice ->
            assertTrue(texts.any { it.contains("Use ${decimalBytes(choice)}") })
        }
    }

    private fun waitForInitialPass(container: ObserverAppContainer): HarnessJournalCachePass {
        repeat(100) {
            container.journalCacheState().latestPass?.let { return it }
            Thread.sleep(100L)
        }
        error("initial local cache pass did not complete")
    }

    // The routine interval cannot elapse during this test, so a pass measured under the chosen
    // limit exists only if the confirmed save requested the immediate pass.
    private fun waitForPassUnderLimit(container: ObserverAppContainer, limitBytes: Long) {
        repeat(100) {
            if (container.journalCacheState().latestPass?.configuredLimitBytes == limitBytes) return
            Thread.sleep(100L)
        }
        error("durable save of ${decimalBytes(limitBytes)} persisted but never caused a local cache pass under it")
    }
}
