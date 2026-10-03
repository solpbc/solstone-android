// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import android.content.ContextWrapper
import android.content.Intent
import app.solstone.core.pl.JournalVersionFreshness
import app.solstone.core.pl.JournalVersionReading
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneAboutPaneTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val facts = AndroidAboutFacts(
        versionName = "v2.1.15",
        build = "24",
        osVersion = "16",
        arch = "aarch64",
    )
    private val reading = JournalVersionReading(
        version = "1.2.3",
        freshness = JournalVersionFreshness.CURRENT,
        os = "ubuntu",
        osVersion = "24.04",
        arch = "x86_64",
    )
    private val expectedBlock =
        "android app 2.1.15 (24) · android 16 · arm64\njournal 1.2.3 · ubuntu 24.04 · x86_64"

    @Test
    fun successfulCopyShowsCopiedAndCopiesTheWholeBlock() {
        var copied: String? = null
        composeRule.setContent {
            PhoneTheme {
                PhoneAboutPane(
                    onOpenLicences = {},
                    reading = reading,
                    copy = { copied = it; true },
                    facts = facts,
                )
            }
        }

        composeRule.onNodeWithText("copy").performClick()
        composeRule.onNodeWithText("copied").assertTextEquals("copied")
        composeRule.runOnIdle { assertEquals(expectedBlock, copied) }
        composeRule.onNodeWithText(expectedBlock).assertTextEquals(expectedBlock)
    }

    @Test
    fun failedCopyKeepsCopyControlAndBlockVisible() {
        composeRule.setContent {
            PhoneTheme {
                PhoneAboutPane(
                    onOpenLicences = {},
                    reading = reading,
                    copy = { false },
                    facts = facts,
                )
            }
        }

        composeRule.onNodeWithText("copy").performClick()
        composeRule.onNodeWithText("copy").assertTextEquals("copy")
        composeRule.onNodeWithText("copied").assertDoesNotExist()
        composeRule.onNodeWithText(expectedBlock).assertTextEquals(expectedBlock)
    }

    @Test
    fun reportKeepsCopiedSnapshotWhenClockAdvancesAndPaneChanges() {
        val now = mutableStateOf(1_000_000L)
        val showHelp = mutableStateOf(false)
        val snapshotEpoch = mutableStateOf(0L)
        val lastKnown = reading.copy(
            freshness = JournalVersionFreshness.LAST_KNOWN,
            versionSeenAt = now.value - 90_000L,
            name = "private-journal-name",
        )
        val expected = "$expectedBlock · last seen 1 minute ago"
        var copied: String? = null
        var opened: Intent? = null
        val context = object : ContextWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
        ) {
            override fun startActivity(intent: Intent) {
                opened = intent
            }
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                PhoneTheme {
                    val block = rememberPhoneAboutBlock(facts, lastKnown, snapshotEpoch.value) { now.value }
                    if (showHelp.value) {
                        PhoneHelpPane(
                            reading = lastKnown,
                            status = PhoneDefaultDetailStatus.Unpaired,
                            aboutBlock = block,
                        )
                    } else {
                        PhoneAboutPane(
                            onOpenLicences = {},
                            reading = lastKnown,
                            copy = { copied = it; true },
                            facts = facts,
                            aboutBlock = block,
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithText(expected).assertTextEquals(expected)
        composeRule.onNodeWithText("copy").performClick()
        composeRule.runOnIdle {
            assertEquals(expected, copied)
            now.value += 8 * 60_000L
            showHelp.value = true
        }
        composeRule.onNodeWithTag("helpReportProblem").performClick()
        composeRule.runOnIdle {
            val fragment = requireNotNull(opened?.data?.encodedFragment)
            val fields = fragment.split('&').associate { part ->
                val (key, value) = part.split('=', limit = 2)
                key to URLDecoder.decode(value, Charsets.UTF_8.name())
            }
            assertEquals(expected, fields["about"])
            assertEquals(copied, fields["about"])
            snapshotEpoch.value++
            showHelp.value = false
        }
        composeRule.onNodeWithText("$expectedBlock · last seen 9 minutes ago")
            .assertTextEquals("$expectedBlock · last seen 9 minutes ago")
    }
}
