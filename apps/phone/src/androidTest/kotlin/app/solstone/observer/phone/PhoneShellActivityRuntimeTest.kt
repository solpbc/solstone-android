// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.PendingIntent
import android.content.Intent
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import app.solstone.observer.formfactor.phone.EXTRA_PHONE_ROUTE
import app.solstone.observer.formfactor.phone.PhoneRoute
import app.solstone.observer.formfactor.phone.encodePhoneRoute
import android.Manifest
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneShellActivityRuntimeTest {
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
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
    }

    @Test
    fun launchesResumedAndRendersLiveSourceTiles() {
        val container = obtainObserverContainer()
        assertTrue(waitForRecovery(container))
        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(sourceTileMatcher()).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("sourceTile-audio").assertIsDisplayed()
            composeRule.onNodeWithTag("sourceTile-location").assertIsDisplayed()
            composeRule.onNodeWithTag("sourceTile-calendar").assertIsDisplayed()
            assertEquals(3, composeRule.onAllNodes(sourceTileMatcher()).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun sourceAttentionIntentReusesShellAndReopensDetailAfterBack() {
        val scenario = ActivityScenario.launch(PhoneShellActivity::class.java)
        var original: PhoneShellActivity? = null
        scenario.onActivity { original = it }
        val intent = Intent(context, PhoneShellActivity::class.java).apply {
            putExtra(EXTRA_PHONE_ROUTE, encodePhoneRoute(PhoneRoute.SourceDetail("audio")))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(context, 99120, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            repeat(2) {
                pending.send()
                composeRule.waitUntil(10_000) {
                    composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("sourceDetailVerdict"))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("sourceDetailVerdict").assertIsDisplayed()
                composeRule.onNode(SemanticsMatcher("audio heading") { node ->
                    node.config.contains(SemanticsProperties.Heading) &&
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "audio" } == true
                }).assertIsDisplayed()
                scenario.onActivity { assertTrue(original === it) }
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                composeRule.waitUntil(10_000) {
                    composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("sourceDetailVerdict"))
                        .fetchSemanticsNodes().isEmpty()
                }
            }
            scenario.onActivity {
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(it, Intent(context, PhoneShellActivity::class.java).apply {
                    putExtra(EXTRA_PHONE_ROUTE, encodePhoneRoute(PhoneRoute.SourceDetail("audio")))
                    flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
                })
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("sourceDetailVerdict").assertDoesNotExist()
        } finally {
            pending.cancel()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { original?.finish() }
            waitUntil("warm source shell destroyed") { original?.isDestroyed == true }
        }
    }

    private fun sourceTileMatcher() = SemanticsMatcher("source tile") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("sourceTile-") == true
    }
}
