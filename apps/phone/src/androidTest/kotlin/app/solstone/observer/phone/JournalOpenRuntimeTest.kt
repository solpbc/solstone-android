// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.solstone.core.diagnostics.DiagnosticLogRead
import app.solstone.core.identity.GraphRevisions
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.browser.JournalBrowserLifecycleListener
import app.solstone.core.pl.browser.JournalBrowserOrigin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class JournalOpenRuntimeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val loadedUrls = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
        loadedUrls.clear()
        PhoneJournalTestHooks.onLoadUrl = { loadedUrls.add(it) }
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
        loadedUrls.clear()
    }

    @Test
    fun posterBuildLaunchIntent_setsFlagsAndValidExtra() {
        val validIntent = JournalPushPoster.buildLaunchIntent(context, "/app/today")
        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        assertEquals(expectedFlags, validIntent.flags)
        assertEquals("/app/today", validIntent.getStringExtra(JournalPushPoster.EXTRA_JOURNAL_OPEN))

        val nullIntent = JournalPushPoster.buildLaunchIntent(context, null)
        assertEquals(expectedFlags, nullIntent.flags)
        assertNull(nullIntent.getStringExtra(JournalPushPoster.EXTRA_JOURNAL_OPEN))

        val traversalIntent = JournalPushPoster.buildLaunchIntent(context, "/app/../secret")
        assertEquals(expectedFlags, traversalIntent.flags)
        assertNull(traversalIntent.getStringExtra(JournalPushPoster.EXTRA_JOURNAL_OPEN))
    }

    @Test
    fun posterNotifications_distinctRequestCodesAndExclusions() {
        val poster = JournalPushPoster(context)
        poster.post(410, "Title 410", "Body 410", "/app/health")
        poster.post(411, "Title 411", "Body 411", "/app/today")

        fun probe(id: Int): PendingIntent? = PendingIntent.getActivity(
            context,
            id,
            Intent(context, PhoneShellActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )

        val pi410 = probe(410)
        val pi411 = probe(411)
        assertNotNull(pi410)
        assertNotNull(pi411)
        assertNotEquals(pi410, pi411)

        assertNull(probe(201))
        assertNull(probe(202))
        assertNull(probe(203))
        assertNull(probe(301))

        val healthIntent = JournalPushPoster.buildLaunchIntent(context, "/app/health")
        assertEquals("/app/health", healthIntent.getStringExtra(JournalPushPoster.EXTRA_JOURNAL_OPEN))

        val todayIntent = JournalPushPoster.buildLaunchIntent(context, "/app/today")
        assertEquals("/app/today", todayIntent.getStringExtra(JournalPushPoster.EXTRA_JOURNAL_OPEN))

        val nullIntent = JournalPushPoster.buildLaunchIntent(context, null)
        assertNull(nullIntent.getStringExtra(JournalPushPoster.EXTRA_JOURNAL_OPEN))

        val invalidIntent = JournalPushPoster.buildLaunchIntent(context, "/app/../x")
        assertNull(invalidIntent.getStringExtra(JournalPushPoster.EXTRA_JOURNAL_OPEN))
    }

    @Test
    fun launchWithValidPath_opensJournalSheetAndLoadsTarget() {
        PhoneJournalTestHooks.pairingSnapshotOverride = committedSnapshot()
        PhoneJournalTestHooks.sessionOverride = { FakeJournalSheetSession("http://127.0.0.1:8080/") }

        val launchIntent = Intent(context, PhoneShellActivity::class.java).apply {
            putExtra(JournalPushPoster.EXTRA_JOURNAL_OPEN, "/app/health")
        }

        ActivityScenario.launch<PhoneShellActivity>(launchIntent).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            composeRule.waitUntil(10_000) { loadedUrls.isNotEmpty() }
            assertEquals(listOf("http://127.0.0.1:8080/app/health"), loadedUrls)

            val log = PhoneDiagLog.installedSink()?.readResult()
            val logContent = when (log) {
                is DiagnosticLogRead.Complete -> log.content
                is DiagnosticLogRead.Partial -> log.content
                else -> ""
            }
            assertTrue("Diag log must record kind=journal_open", logContent.contains("kind=journal_open"))
            assertFalse("fixture path leaked in diag log", logContent.contains("/app/health"))
        }
    }

    @Test
    fun launchWithInvalidPath_doesNotOpenSheetOrLoadTarget() {
        PhoneJournalTestHooks.pairingSnapshotOverride = committedSnapshot()
        PhoneJournalTestHooks.sessionOverride = { FakeJournalSheetSession("http://127.0.0.1:8080/") }

        val launchIntent = Intent(context, PhoneShellActivity::class.java).apply {
            putExtra(JournalPushPoster.EXTRA_JOURNAL_OPEN, "/app/../x")
        }

        ActivityScenario.launch<PhoneShellActivity>(launchIntent).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            composeRule.waitForIdle()
            Thread.sleep(500)
            assertTrue("Invalid open path must not trigger web load", loadedUrls.isEmpty())
            composeRule.onNodeWithText("close").assertDoesNotExist()
        }
    }

    @Test
    fun pushWhileResumed_loadsNewPathInNewInstance() {
        PhoneJournalTestHooks.pairingSnapshotOverride = committedSnapshot()
        PhoneJournalTestHooks.sessionOverride = { FakeJournalSheetSession("http://127.0.0.1:8080/") }

        val launchIntent = Intent(context, PhoneShellActivity::class.java).apply {
            putExtra(JournalPushPoster.EXTRA_JOURNAL_OPEN, "/app/health")
        }

        val scenario = ActivityScenario.launch<PhoneShellActivity>(launchIntent)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(PhoneShellActivity::class.java.name, null, false)

        try {
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            composeRule.waitUntil(10_000) {
                loadedUrls.contains("http://127.0.0.1:8080/app/health")
            }

            JournalPushPoster(context).post(420, "Title 420", "Body 420", "/app/today")

            val pendingIntent = PendingIntent.getActivity(
                context,
                420,
                Intent(context, PhoneShellActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            assertNotNull(pendingIntent)

            pendingIntent.send()

            val nextActivity = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
            assertNotNull("New activity instance must arrive after send", nextActivity)
            composeRule.waitUntil(10_000) {
                loadedUrls.contains("http://127.0.0.1:8080/app/today")
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            runCatching { scenario.close() }
        }
    }

    @Test
    fun recreateActivity_doesNotReopenOrReload() {
        PhoneJournalTestHooks.pairingSnapshotOverride = committedSnapshot()
        PhoneJournalTestHooks.sessionOverride = { FakeJournalSheetSession("http://127.0.0.1:8080/") }

        val launchIntent = Intent(context, PhoneShellActivity::class.java).apply {
            putExtra(JournalPushPoster.EXTRA_JOURNAL_OPEN, "/app/health")
        }

        ActivityScenario.launch<PhoneShellActivity>(launchIntent).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            composeRule.waitUntil(10_000) { loadedUrls.isNotEmpty() }
            assertEquals(1, loadedUrls.size)

            composeRule.onNodeWithText("close").performClick()
            composeRule.waitForIdle()
            loadedUrls.clear()
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            composeRule.waitForIdle()
            Thread.sleep(500)
            assertTrue("Recreating activity must not re-open journal path", loadedUrls.isEmpty())
        }
    }

    @Test
    fun journalOpen_diagAndLogcatPrivacy() {
        val fixturePath = "/app/privacycheckdistincttarget"
        Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()

        PhoneJournalTestHooks.pairingSnapshotOverride = committedSnapshot()
        PhoneJournalTestHooks.sessionOverride = { FakeJournalSheetSession("http://127.0.0.1:8080/") }

        val launchIntent = Intent(context, PhoneShellActivity::class.java).apply {
            putExtra(JournalPushPoster.EXTRA_JOURNAL_OPEN, fixturePath)
        }

        ActivityScenario.launch<PhoneShellActivity>(launchIntent).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            composeRule.waitUntil(10_000) {
                val log = PhoneDiagLog.installedSink()?.readResult()
                val content = when (log) {
                    is DiagnosticLogRead.Complete -> log.content
                    is DiagnosticLogRead.Partial -> log.content
                    else -> ""
                }
                content.contains("kind=journal_open")
            }

            val log = PhoneDiagLog.installedSink()?.readResult()
            val logContent = when (log) {
                is DiagnosticLogRead.Complete -> log.content
                is DiagnosticLogRead.Partial -> log.content
                else -> ""
            }
            assertTrue("Diag log must record kind=journal_open", logContent.contains("kind=journal_open"))
            assertFalse("fixture path leaked in diag log", logContent.contains(fixturePath))

            val logcatContent = Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream.bufferedReader().use { it.readText() }
            assertFalse("fixture path leaked in logcat", logcatContent.contains(fixturePath))
        }
    }

    private fun committedSnapshot(): PairingGraphSnapshot.Committed {
        val home = PairedHome(
            instanceId = "test-instance",
            homeLabel = "Test Journal",
            relayOrigin = null,
            caChainFingerprint = "ca-fp",
            clientCertFingerprint = "client-fp",
            observerHandle = "obs-1",
            deviceToken = null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        return PairingGraphSnapshot.Committed(
            sequenceNumber = 1L,
            revisions = GraphRevisions(1L, 1L, 1L),
            home = home,
            hasDirectEndpoint = false,
            directAssociated = false,
            relayLiveEligible = false,
        )
    }

    private class FakeJournalSheetSession(
        private val originUrl: String = "http://127.0.0.1:8080/",
    ) : JournalSheetSession {
        override fun addLifecycleListener(listener: JournalBrowserLifecycleListener) {}
        override fun removeLifecycleListener(listener: JournalBrowserLifecycleListener) {}
        override fun start(): JournalBrowserOrigin = JournalBrowserOrigin(originUrl)
        override fun stop() {}
    }
}
