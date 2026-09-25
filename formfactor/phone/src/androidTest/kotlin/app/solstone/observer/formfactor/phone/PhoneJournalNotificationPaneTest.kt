// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PhoneJournalNotificationPaneTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun rowVariantOn() {
        composeTestRule.setContent {
            PhoneNotificationsPane(
                enabled = true,
                onOpenSettings = {},
                onSendTest = {},
                journalPushEnabled = true,
                journalNotificationRow = PhoneJournalNotificationRow.On,
            )
        }

        composeTestRule.onNodeWithText(JOURNAL_FROM_YOUR_JOURNAL).assertIsDisplayed()
        composeTestRule.onNodeWithText(JOURNAL_NOTIFICATIONS_ON).assertIsDisplayed()
        composeTestRule.onNodeWithText("open notification settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("send test notification").assertIsDisplayed()
        composeTestRule.onNodeWithText("when there's something worth a look").assertIsDisplayed()
        composeTestRule.onNodeWithText("a short heads-up, never the content").assertIsDisplayed()
    }

    @Test
    fun rowVariantNeedsDeliveryApp() {
        composeTestRule.setContent {
            PhoneNotificationsPane(
                enabled = true,
                onOpenSettings = {},
                onSendTest = {},
                journalPushEnabled = true,
                journalNotificationRow = PhoneJournalNotificationRow.NeedsDeliveryApp,
            )
        }

        composeTestRule.onNodeWithText(JOURNAL_FROM_YOUR_JOURNAL).assertIsDisplayed()
        composeTestRule.onNodeWithText(JOURNAL_NOT_REACHING).assertIsDisplayed()
        composeTestRule.onNodeWithText(JOURNAL_GET_NTFY).assertIsDisplayed()
        composeTestRule.onNodeWithText(JOURNAL_GET_NTFY_SUB).assertIsDisplayed()
        composeTestRule.onNodeWithText(JOURNAL_NO_APP_NOTE).assertIsDisplayed()
    }

    @Test
    fun rowVariantChooseDeliveryApp() {
        var clicked = 0
        composeTestRule.setContent {
            PhoneNotificationsPane(
                enabled = true,
                onOpenSettings = {},
                onSendTest = {},
                journalPushEnabled = true,
                journalNotificationRow = PhoneJournalNotificationRow.ChooseDeliveryApp,
                onChooseJournalDeliveryApp = { clicked++ },
            )
        }

        composeTestRule.onNodeWithText(JOURNAL_FROM_YOUR_JOURNAL).assertIsDisplayed()
        composeTestRule.onNodeWithText(JOURNAL_CHOOSE_VALUE).assertIsDisplayed()
        composeTestRule.onNodeWithText(JOURNAL_CHOOSE_SUB).assertIsDisplayed()

        composeTestRule.onNodeWithTag("notificationsChooseApp").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun rowVariantInsecureAddress() {
        composeTestRule.setContent {
            PhoneNotificationsPane(
                enabled = true,
                onOpenSettings = {},
                onSendTest = {},
                journalPushEnabled = true,
                journalNotificationRow = PhoneJournalNotificationRow.InsecureAddress,
            )
        }

        composeTestRule.onNodeWithText(JOURNAL_FROM_YOUR_JOURNAL).assertIsDisplayed()
        composeTestRule.onNodeWithText(JOURNAL_NOT_REACHING).assertIsDisplayed()
        composeTestRule.onNodeWithText(JOURNAL_INSECURE_NOTE).assertIsDisplayed()
    }

    @Test
    fun rowVariantDeliveryAppStopped() {
        val appName = "Ntfy"
        val expectedNote = journalDeliveryAppStoppedNote(appName)
        composeTestRule.setContent {
            PhoneNotificationsPane(
                enabled = true,
                onOpenSettings = {},
                onSendTest = {},
                journalPushEnabled = true,
                journalNotificationRow = PhoneJournalNotificationRow.DeliveryAppStopped(appName),
            )
        }

        composeTestRule.onNodeWithText(JOURNAL_FROM_YOUR_JOURNAL).assertIsDisplayed()
        composeTestRule.onNodeWithText(JOURNAL_NOT_REACHING).assertIsDisplayed()
        composeTestRule.onNodeWithText(expectedNote).assertIsDisplayed()
    }

    @Test
    fun nullJournalNotificationRowShowsNothingNew() {
        composeTestRule.setContent {
            PhoneNotificationsPane(
                enabled = true,
                onOpenSettings = {},
                onSendTest = {},
                journalPushEnabled = true,
                journalNotificationRow = null,
            )
        }

        composeTestRule.onNodeWithText(JOURNAL_FROM_YOUR_JOURNAL).assertDoesNotExist()
        composeTestRule.onNodeWithText(JOURNAL_NOT_REACHING).assertDoesNotExist()
        composeTestRule.onNodeWithText(JOURNAL_NO_APP_NOTE).assertDoesNotExist()
        composeTestRule.onNodeWithText(JOURNAL_GET_NTFY).assertDoesNotExist()
        composeTestRule.onNodeWithText(JOURNAL_GET_NTFY_SUB).assertDoesNotExist()
        composeTestRule.onNodeWithText(JOURNAL_CHOOSE_VALUE).assertDoesNotExist()
        composeTestRule.onNodeWithText(JOURNAL_CHOOSE_SUB).assertDoesNotExist()
        composeTestRule.onNodeWithText(JOURNAL_INSECURE_NOTE).assertDoesNotExist()

        composeTestRule.onNodeWithText("open notification settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("when there's something worth a look").assertIsDisplayed()
        composeTestRule.onNodeWithText("a short heads-up, never the content").assertIsDisplayed()
    }

    @Test
    fun flagFalseShowsNothingNewEvenWithOnRow() {
        composeTestRule.setContent {
            PhoneNotificationsPane(
                enabled = true,
                onOpenSettings = {},
                onSendTest = {},
                journalPushEnabled = false,
                journalNotificationRow = PhoneJournalNotificationRow.On,
            )
        }

        composeTestRule.onNodeWithText(JOURNAL_FROM_YOUR_JOURNAL).assertDoesNotExist()
        composeTestRule.onNodeWithText("open notification settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("when there's something worth a look").assertIsDisplayed()
        composeTestRule.onNodeWithText("a short heads-up, never the content").assertIsDisplayed()
    }
}
