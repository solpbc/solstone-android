// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.browser.JournalBrowserLifecycleListener
import app.solstone.core.pl.browser.JournalBrowserOrigin
import app.solstone.observer.scaffold.ObserverActivity
import app.solstone.platform.work.confirmCurrentJournal
import app.solstone.platform.work.syncStores
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PhoneConfirmationShellTest {
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

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
        PhoneShellActivity.promptedConfirmationThisProcess = false
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
        PhoneJournalTestHooks.reset()
        PhoneShellActivity.promptedConfirmationThisProcess = false
        runCatching { syncStores(context).publisher.forget() }
    }

    @Test
    fun confirmationPolicyConsultsIsTrue() {
        assertTrue(JournalConfirmationPolicy.consults)
    }

    @Test
    fun unconfirmedPairingPromptsObserverActivityAndFlowCompletes() {
        val container = obtainObserverContainer()
        assertTrue(waitForRecovery(container))
        val stores = syncStores(context)
        val publisher = stores.publisher
        val confirmStore = stores.journalConfirmationStore

        publisher.forget()

        val home = PairedHome(
            instanceId = "home-held",
            homeLabel = "Home",
            relayOrigin = "https://link.solstone.app",
            caChainFingerprint = "sha256:ca-1",
            clientCertFingerprint = "sha256:held-shell",
            observerHandle = "obs",
            deviceToken = "token-1",
            expiresAt = "2030-01-01T00:00:00Z",
            state = IdentityState.PAIRED,
        )
        val cred = ClientCredential(
            privateKeyPem = "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----\n",
            clientCertPem = "-----BEGIN CERTIFICATE-----\ncert\n-----END CERTIFICATE-----\n",
            caChainPem = listOf("-----BEGIN CERTIFICATE-----\nca\n-----END CERTIFICATE-----\n"),
        )
        val installResult = publisher.installOrReplace(home, cred, null, false)
        assertTrue(installResult is GraphMutationResult.Applied)

        confirmStore.confirm("sha256:not-this")
        PhoneShellActivity.promptedConfirmationThisProcess = false

        val sessionCalls = AtomicInteger(0)
        PhoneJournalTestHooks.sessionOverride = {
            sessionCalls.incrementAndGet()
            FakeJournalSheetSession("http://127.0.0.1:8080/")
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(ObserverActivity::class.java.name, null, false)

        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            val observerActivity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? ObserverActivity
            assertNotNull("ObserverActivity must be started", observerActivity)
            assertTrue(observerActivity!!.intent.getBooleanExtra(ObserverActivity.EXTRA_CONFIRM_JOURNAL, false))

            composeRule.onNodeWithText("does this match your journal?").assertExists()

            val labels = buttonLabels(observerActivity)
            MENU_ONLY_CONTROLS.forEach {
                assertFalse("ObserverActivity must not show operator control $it; saw $labels", labels.contains(it))
            }
            assertFalse("ObserverActivity must not draw Back; saw $labels", labels.contains("Back"))

            assertEquals(0, sessionCalls.get())
            composeRule.onNodeWithText("close").assertDoesNotExist()

            instrumentation.runOnMainSync {
                @Suppress("DEPRECATION")
                observerActivity.onBackPressed()
            }
            assertTrue("ObserverActivity must be finishing after back", observerActivity.isFinishing)
            instrumentation.waitForIdleSync()

            // After returning to shell, those 5 labels are still absent
            scenario.onActivity { shell ->
                val shellLabels = buttonLabels(shell)
                MENU_ONLY_CONTROLS.forEach {
                    assertFalse("Shell must not show operator control $it; saw $shellLabels", shellLabels.contains(it))
                }
            }

            val hitsBeforeRecreate = monitor.hits
            scenario.recreate()
            instrumentation.waitForIdleSync()
            assertEquals("Recreate must not start another ObserverActivity", hitsBeforeRecreate, monitor.hits)
        }

        // Close that scenario, set prompt flag false, launch shell again
        PhoneShellActivity.promptedConfirmationThisProcess = false
        val monitor2 = instrumentation.addMonitor(ObserverActivity::class.java.name, null, false)
        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            val observerActivity2 = instrumentation.waitForMonitorWithTimeout(monitor2, 5000) as? ObserverActivity
            assertNotNull("ObserverActivity must be started on fresh launch", observerActivity2)
            assertTrue(observerActivity2!!.intent.getBooleanExtra(ObserverActivity.EXTRA_CONFIRM_JOURNAL, false))
            instrumentation.runOnMainSync {
                @Suppress("DEPRECATION")
                observerActivity2.onBackPressed()
            }
            instrumentation.waitForIdleSync()

            // On shell, confirmCurrentJournal is true
            val confirmed = confirmCurrentJournal(publisher, confirmStore, "sha256:held-shell")
            assertTrue(confirmed)

            val hitsBeforeRecreate = monitor2.hits
            scenario.recreate()
            instrumentation.waitForIdleSync()
            assertEquals("Recreate after confirm must not start another ObserverActivity", hitsBeforeRecreate, monitor2.hits)

            assertEquals(0, sessionCalls.get())
            composeRule.onNodeWithText("close").assertDoesNotExist()

            val hitsBeforeClick = monitor2.hits
            composeRule.onNodeWithTag("journalMarkPill").performClick()
            composeRule.waitUntil(10_000) {
                sessionCalls.get() > 0
            }
            composeRule.onNodeWithText("close").assertExists()
            assertEquals("Pill click must not start another ObserverActivity", hitsBeforeClick, monitor2.hits)
        }
    }

    private fun buttonLabels(activity: Activity): List<String> =
        buildList { collectButtons(activity.findViewById(android.R.id.content), this) }

    private fun collectButtons(view: View, into: MutableList<String>) {
        if (view is Button) into += view.text.toString()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectButtons(view.getChildAt(i), into)
        }
    }

    private class FakeJournalSheetSession(
        private val originUrl: String = "http://127.0.0.1:8080/",
    ) : JournalSheetSession {
        override fun addLifecycleListener(listener: JournalBrowserLifecycleListener) {}
        override fun removeLifecycleListener(listener: JournalBrowserLifecycleListener) {}
        override fun start(): JournalBrowserOrigin = JournalBrowserOrigin(originUrl)
        override fun stop() {}
    }

    private companion object {
        val MENU_ONLY_CONTROLS = listOf(
            "Permissions",
            "PL status probe",
            "Start/stop intake",
            "Status + queue/sync",
            "Evidence + export",
        )
    }
}
