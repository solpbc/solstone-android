// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.pl.JournalVersionFreshness
import app.solstone.core.pl.JournalVersionReading
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    fun failedCopyShowsRecoveryTextAndKeepsBlockVisible() {
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
        composeRule.onNodeWithText("couldn't copy. select the text and copy it.").assertTextEquals(
            "couldn't copy. select the text and copy it.",
        )
        composeRule.onNodeWithText(expectedBlock).assertTextContains("journal 1.2.3")
    }
}
