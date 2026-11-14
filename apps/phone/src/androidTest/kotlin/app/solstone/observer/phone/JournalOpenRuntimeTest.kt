// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.solstone.core.diagnostics.DiagnosticLogRead
import app.solstone.core.identity.GraphRevisions
import app.solstone.core.identity.ObtainResult
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PushKeyAccess
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.browser.JournalBrowserLifecycleListener
import app.solstone.core.pl.browser.JournalBrowserOrigin
import app.solstone.core.push.PushEventSerial
import app.solstone.core.push.PushMessageHandler
import app.solstone.core.push.PushRegistrationCoordinator
import app.solstone.platform.work.syncStores
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        syncStores(context).journalConfirmationStore.confirm("client-fp")
        loadedUrls.clear()
        PhoneJournalTestHooks.onLoadUrl = { loadedUrls.add(it) }
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
        loadedUrls.clear()
    }

    @Test
    fun queuedJournalPushDoesNotPostAfterTurnOff() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val notificationId = 8510
        fun pendingIntent(): PendingIntent? = PendingIntent.getActivity(
            context, notificationId, JournalPushPoster.buildLaunchIntent(context, null),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        val directory = context.cacheDir.resolve("push-consent-post-fixture")
        val keys = object : PushKeyAccess {
            override fun readPushKey(generation: PairingGeneration): ByteArray? = null
            override fun obtainPushKey(generation: PairingGeneration): ObtainResult = error("unused")
        }
        val coordinator = PushRegistrationCoordinator(
            directory, null, true, keys, { null }, log = {}, enqueue = {},
        )
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val handler = PushMessageHandler(
            keys, { null }, coordinator.guardNotifier(JournalPushPoster(context)),
            log = {}, drawId = { notificationId },
        )
        val serial = PushEventSerial(coordinator, handler, executor)
        try {
            manager.cancel(notificationId)
            pendingIntent()?.cancel()
            coordinator.setOwnerOn(true)
            // Positive control: this permission/channel and real Android adapter can post.
            handler.onMessage(byteArrayOf(), false)
            waitUntil("fixture notification posted") { manager.activeNotifications.any { it.id == notificationId } }
            assertNotNull(pendingIntent())
            manager.cancel(notificationId)
            pendingIntent()?.cancel()
            waitUntil("fixture notification removed") { manager.activeNotifications.none { it.id == notificationId } }

            val blocker = executor.submit { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            serial.onMessage(byteArrayOf(), false)
            coordinator.setOwnerOn(false)
            release.countDown()
            blocker.get(5, TimeUnit.SECONDS)
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertFalse(coordinator.ownerOn)
            // Intent creation is synchronous; OS notification visibility can lag a post.
            assertNull(pendingIntent())
            assertTrue(manager.activeNotifications.none { it.id == notificationId })

            coordinator.setOwnerOn(true)
            serial.onMessage(byteArrayOf(), false)
            executor.submit {}.get(5, TimeUnit.SECONDS)
            waitUntil("fixture notification after enabling") { manager.activeNotifications.any { it.id == notificationId } }
        } finally {
            release.countDown()
            serial.close()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            coordinator.close()
            manager.cancel(notificationId)
            pendingIntent()?.cancel()
            directory.deleteRecursively()
        }
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
        // 203 is the app notification route and may already exist in this shared test process.
        assertNotEquals(pi410, probe(203))
        assertNotEquals(pi411, probe(203))
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
    fun recreatingTheActivity_keepsTheJournalOpen() {
        PhoneJournalTestHooks.pairingSnapshotOverride = committedSnapshot()
        PhoneJournalTestHooks.sessionOverride = { FakeJournalSheetSession("http://127.0.0.1:8080/") }

        val launchIntent = Intent(context, PhoneShellActivity::class.java).apply {
            putExtra(JournalPushPoster.EXTRA_JOURNAL_OPEN, "/app/health")
        }

        ActivityScenario.launch<PhoneShellActivity>(launchIntent).use { scenario ->
            composeRule.waitUntil(10_000) { loadedUrls.size == 1 }
            scenario.recreate()
            composeRule.waitUntil(10_000) { loadedUrls.size == 2 }
        }
    }

    @Test
    fun journalWebViewContext_followsTheSystemAppearance() {
        fun isLight(nightMode: Int): Boolean {
            val configuration = android.content.res.Configuration(context.resources.configuration)
            configuration.uiMode = nightMode or
                (configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv())
            val typed = android.util.TypedValue()
            journalWebViewContext(context.createConfigurationContext(configuration))
                .theme.resolveAttribute(android.R.attr.isLightTheme, typed, true)
            return typed.data != 0
        }
        assertTrue(isLight(android.content.res.Configuration.UI_MODE_NIGHT_NO))
        assertFalse(isLight(android.content.res.Configuration.UI_MODE_NIGHT_YES))
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
    fun pushWhileResumed_reusesShellAndReloadsEachDelivery() {
        PhoneJournalTestHooks.pairingSnapshotOverride = committedSnapshot()
        PhoneJournalTestHooks.sessionOverride = { FakeJournalSheetSession("http://127.0.0.1:8080/") }

        val launchIntent = Intent(context, PhoneShellActivity::class.java).apply {
            putExtra(JournalPushPoster.EXTRA_JOURNAL_OPEN, "/app/health")
        }

        val scenario = ActivityScenario.launch<PhoneShellActivity>(launchIntent)
        var originalActivity: PhoneShellActivity? = null
        scenario.onActivity { originalActivity = it }

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

            composeRule.waitUntil(10_000) {
                loadedUrls.count { it == "http://127.0.0.1:8080/app/today" } == 1
            }
            scenario.onActivity { assertTrue("Warm push must reuse the shell", originalActivity === it) }
            pendingIntent.send()
            composeRule.waitUntil(10_000) {
                loadedUrls.count { it == "http://127.0.0.1:8080/app/today" } == 2
            }
        } finally {
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

    @Test
    fun journalLinksOpenColdAndWarmWithNotificationsOffAndRepeatExactly() {
        PhoneJournalTestHooks.pairingSnapshotOverride = committedSnapshot()
        PhoneJournalTestHooks.sessionOverride = { FakeJournalSheetSession("http://127.0.0.1:8080/") }
        syncStores(context).setJournalPushOn(false)
        val first = "journal_link_cold_0123456789~123456"
        val second = "journal_link_warm_9876543210"
        val launch = journalLinkIntent(first)
        JournalLinkActivity(launch).use { scenario ->
            composeRule.waitUntil(10_000) { loadedUrls.size == 1 }
            assertEquals("http://127.0.0.1:8080/app/open/$first", loadedUrls.last())
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertNull(activity.intent.data)
                activity.startActivity(journalLinkIntent(second))
            }
            composeRule.waitUntil(10_000) { loadedUrls.size == 2 }
            assertEquals("http://127.0.0.1:8080/app/open/$second", loadedUrls.last())
            scenario.onActivity { it.startActivity(journalLinkIntent(second)) }
            composeRule.waitUntil(10_000) { loadedUrls.size == 3 }
            assertEquals("http://127.0.0.1:8080/app/open/$second", loadedUrls.last())
            val log = PhoneDiagLog.installedSink()?.readAll().orEmpty()
            assertTrue(log.contains("kind=journal_link outcome=accepted"))
            assertFalse(log.contains(first))
            assertFalse(log.contains(second))
        }
    }

    @Test
    fun malformedLinkShowsNoticeWithoutOpeningTheJournalOrPairing() {
        PhoneJournalTestHooks.pairingSnapshotOverride = committedSnapshot()
        PhoneJournalTestHooks.sessionOverride = { error("invalid link started a journal session") }
        JournalLinkActivity(journalLinkIntent("invalid=token")).use { scenario ->
            composeRule.onNode(isDialog()).assertExists()
            composeRule.onNodeWithText("connect").assertDoesNotExist()
            assertTrue(loadedUrls.isEmpty())
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }

    @Test
    fun retryAfterFailedLoadAndRecreationKeepsOriginalLink() {
        PhoneJournalTestHooks.pairingSnapshotOverride = committedSnapshot()
        var fail = true
        PhoneJournalTestHooks.sessionOverride = {
            if (fail) object : JournalSheetSession {
                override fun addLifecycleListener(listener: JournalBrowserLifecycleListener) {}
                override fun removeLifecycleListener(listener: JournalBrowserLifecycleListener) {}
                override fun start(): JournalBrowserOrigin = error("fixture unreachable")
                override fun stop() {}
            } else FakeJournalSheetSession("http://127.0.0.1:8080/")
        }
        val token = "journal_link_retry_0123456789~012345"
        JournalLinkActivity(journalLinkIntent(token)).use { scenario ->
            composeRule.onNodeWithText("try again").assertExists()
            scenario.recreate()
            composeRule.onNodeWithText("try again").assertExists()
            fail = false
            composeRule.onNodeWithText("try again").performClick()
            composeRule.waitUntil(10_000) { loadedUrls.isNotEmpty() }
            assertEquals(listOf("http://127.0.0.1:8080/app/open/$token"), loadedUrls)
        }
    }

    @Test
    fun latestLinkWaitsForMarkConfirmationAndSurvivesRecreation() {
        seedUnconfirmedLinkPairing()
        PhoneJournalTestHooks.sessionOverride = { FakeJournalSheetSession("http://127.0.0.1:8080/") }
        val latest = "journal_link_latest_0123456789~112233"
        val pairingLaunch = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://go.solstone.app/p#fixture"))
            .setClass(context, PhoneShellActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        JournalLinkActivity(pairingLaunch).use { scenario ->
            composeRule.waitForIdle()
            assertTrue(loadedUrls.isEmpty())
            scenario.onActivity { it.startActivity(journalLinkIntent("journal_link_earlier")) }
            composeRule.waitForIdle()
            scenario.onActivity { it.startActivity(journalLinkIntent(latest)) }
            composeRule.waitForIdle()
            scenario.recreate()
            composeRule.waitForIdle()
            assertTrue(loadedUrls.isEmpty())
            composeRule.onNodeWithText("yes, this is my journal").performScrollTo().performClick()
            composeRule.waitUntil(10_000) { loadedUrls.isNotEmpty() }
            assertEquals(listOf("http://127.0.0.1:8080/app/open/$latest"), loadedUrls)
        }
    }

    @Test
    fun unpairedLinkWaitsForSetupAndConfirmationWithoutLosingTheLatestTarget() {
        assertTrue(syncStores(context).publisher.forget() is app.solstone.core.identity.GraphMutationResult.Cleared)
        assertTrue(syncStores(context).publisher.currentSnapshot() is PairingGraphSnapshot.Absent)
        PhoneJournalTestHooks.sessionOverride = { FakeJournalSheetSession("http://127.0.0.1:8080/") }
        val latest = "journal_link_setup_latest_0123456789"
        JournalLinkActivity(journalLinkIntent("journal_link_before_setup")).use { scenario ->
            composeRule.waitForIdle()
            assertTrue(loadedUrls.isEmpty())
            scenario.onActivity { it.startActivity(journalLinkIntent(latest)) }
            composeRule.waitForIdle()
            scenario.recreate()
            composeRule.waitForIdle()
            assertTrue(loadedUrls.isEmpty())
            seedUnconfirmedLinkPairing()
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithTag("journalMarkPill").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(loadedUrls.isEmpty())
            composeRule.onNodeWithTag("journalMarkPill").performClick()
            composeRule.onNodeWithText("yes, this is my journal").performScrollTo().performClick()
            composeRule.waitUntil(10_000) { loadedUrls.isNotEmpty() }
            assertEquals(listOf("http://127.0.0.1:8080/app/open/$latest"), loadedUrls)
        }
    }

    private fun seedUnconfirmedLinkPairing() {
        val container = obtainObserverContainer()
        assertTrue(waitForRecovery(container))
        val stores = syncStores(context)
        stores.publisher.forget()
        stores.journalConfirmationStore.confirm("not-this-journal")
        val credential = app.solstone.core.identity.ClientCredential(
            privateKeyPem = "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----\n",
            clientCertPem = "-----BEGIN CERTIFICATE-----\ncert\n-----END CERTIFICATE-----\n",
            caChainPem = listOf("-----BEGIN CERTIFICATE-----\nca\n-----END CERTIFICATE-----\n"),
        )
        assertTrue(stores.publisher.installOrReplace(committedSnapshot().home, credential, null, false)
            is app.solstone.core.identity.GraphMutationResult.Applied)
        val pairing = (stores.publisher.currentSnapshot() as PairingGraphSnapshot.Committed).pairing
        val mark = app.solstone.core.identity.JournalMark(
            app.solstone.core.identity.JournalMarkIcon("piano", "<path d=\"M18.5 3H5.5\"/>", "blue", "#3b82f6", 45),
            app.solstone.core.identity.JournalMarkIcon("key", "<circle cx=\"7.5\" cy=\"15.5\" r=\"5.5\"/>", "purple", "#a855f7", 0),
            listOf("liquefy", "smock"),
        )
        stores.journalMarkStore.save(app.solstone.core.identity.JournalMarkRecord(pairing.instanceId, mark, pairing, derived = true))
        stores.journalIdentityCoordinator.onPairingChanged()
        stores.journalConfirmationStore.confirm("not-this-journal")
        PhoneShellActivity.promptedConfirmationThisProcess = false
    }

    // ActivityScenario matches lifecycle events by the original data URI. Link intake
    // consumes that URI, so observe the activity itself and own its teardown instead.
    private inner class JournalLinkActivity(launch: Intent) : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val application = context.applicationContext as Application
        private val activities = CopyOnWriteArrayList<PhoneShellActivity>()
        @Volatile private var current: PhoneShellActivity? = null
        @Volatile private var resumed = false
        private var expectedActivityCount = 1
        private val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is PhoneShellActivity) {
                    activities.add(activity)
                    current = activity
                }
            }
            override fun onActivityResumed(activity: Activity) {
                if (activity === current) resumed = true
            }
            override fun onActivityPaused(activity: Activity) {
                if (activity === current) resumed = false
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        init {
            application.registerActivityLifecycleCallbacks(callbacks)
            try {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                composeRule.waitUntil(15_000) { resumed && current != null }
            } catch (failure: Throwable) {
                try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
        fun onActivity(block: (PhoneShellActivity) -> Unit) {
            instrumentation.runOnMainSync {
                assertEquals("unexpected task replacement", expectedActivityCount, activities.size)
                block(checkNotNull(current))
            }
        }
        fun recreate() {
            val before = current
            onActivity { expectedActivityCount++; it.recreate() }
            composeRule.waitUntil(15_000) { current !== before && resumed }
            assertEquals("unexpected activity after recreation", expectedActivityCount, activities.size)
        }
        override fun close() {
            try {
                instrumentation.runOnMainSync { activities.filterNot { it.isDestroyed }.forEach { it.finish() } }
                composeRule.waitUntil(15_000) { activities.all { it.isDestroyed } }
                assertEquals("unexpected task replacement", expectedActivityCount, activities.size)
            } finally {
                application.unregisterActivityLifecycleCallbacks(callbacks)
            }
        }
    }

    private fun journalLinkIntent(token: String): Intent = Intent.parseUri(
        "intent://go.solstone.app/j#$token#Intent;scheme=https;package=app.solstone.observer.phone;end",
        Intent.URI_INTENT_SCHEME,
    ).setClass(context, PhoneShellActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

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
