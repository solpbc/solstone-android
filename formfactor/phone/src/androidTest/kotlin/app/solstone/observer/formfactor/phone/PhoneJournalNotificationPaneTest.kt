// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
                journalPushOn = true,
                journalNotificationRow = PhoneJournalNotificationRow.On,
            )
        }

        // The switch says on, so the journal row is the switch alone.
        composeTestRule.onAllNodesWithText(JOURNAL_FROM_YOUR_JOURNAL).assertCountEquals(1)
        composeTestRule.onNodeWithText(JOURNAL_SWITCH_SUB).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(JOURNAL_NOTIFICATIONS_ON).assertCountEquals(1)
        composeTestRule.onNodeWithText(JOURNAL_DELIVERED_BY).assertDoesNotExist()
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
                journalPushOn = true,
                journalNotificationRow = PhoneJournalNotificationRow.NeedsDeliveryApp,
            )
        }

        composeTestRule.onAllNodesWithText(JOURNAL_FROM_YOUR_JOURNAL).assertCountEquals(2)
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
                journalPushOn = true,
                journalNotificationRow = PhoneJournalNotificationRow.ChooseDeliveryApp,
                onChooseJournalDeliveryApp = { clicked++ },
            )
        }

        composeTestRule.onAllNodesWithText(JOURNAL_FROM_YOUR_JOURNAL).assertCountEquals(2)
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
                journalPushOn = true,
                journalNotificationRow = PhoneJournalNotificationRow.InsecureAddress,
            )
        }

        composeTestRule.onAllNodesWithText(JOURNAL_FROM_YOUR_JOURNAL).assertCountEquals(2)
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
                journalPushOn = true,
                journalNotificationRow = PhoneJournalNotificationRow.DeliveryAppStopped(appName),
            )
        }

        composeTestRule.onAllNodesWithText(JOURNAL_FROM_YOUR_JOURNAL).assertCountEquals(2)
        composeTestRule.onNodeWithText(JOURNAL_NOT_REACHING).assertIsDisplayed()
        composeTestRule.onNodeWithText(expectedNote).assertIsDisplayed()
    }

    @Test
    fun offByDefaultShowsOnlyTheSwitch() {
        composeTestRule.setContent {
            PhoneNotificationsPane(
                enabled = true,
                onOpenSettings = {},
                onSendTest = {},
                journalPushEnabled = true,
                journalNotificationRow = PhoneJournalNotificationRow.NeedsDeliveryApp,
                journalDeliveredBy = "ntfy",
            )
        }

        composeTestRule.onAllNodesWithText(JOURNAL_FROM_YOUR_JOURNAL).assertCountEquals(1)
        composeTestRule.onNodeWithTag("notificationsJournalSwitch").assertIsOff()
        composeTestRule.onNodeWithText(JOURNAL_SWITCH_SUB).assertIsDisplayed()
        composeTestRule.onNodeWithText(JOURNAL_DELIVERED_BY).assertDoesNotExist()
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
    fun switchReportsTheOwnersChoice() {
        val changes = mutableListOf<Boolean>()
        composeTestRule.setContent {
            PhoneNotificationsPane(
                enabled = true,
                onOpenSettings = {},
                onSendTest = {},
                journalPushEnabled = true,
                onJournalPushChange = { changes += it },
            )
        }

        composeTestRule.onNodeWithTag("notificationsJournalSwitch").performClick()
        assertEquals(listOf(true), changes)
    }

    @Test
    fun deliveredByShowsWhenOnAndOpensTheChooser() {
        var clicked = 0
        composeTestRule.setContent {
            PhoneNotificationsPane(
                enabled = true,
                onOpenSettings = {},
                onSendTest = {},
                journalPushEnabled = true,
                journalPushOn = true,
                journalNotificationRow = PhoneJournalNotificationRow.On,
                journalDeliveredBy = "ntfy",
                onChooseJournalDeliveryApp = { clicked++ },
            )
        }

        composeTestRule.onNodeWithTag("notificationsJournalSwitch").assertIsOn()
        composeTestRule.onNodeWithText(JOURNAL_DELIVERED_BY).assertIsDisplayed()
        composeTestRule.onNodeWithText("ntfy").assertIsDisplayed()
        composeTestRule.onNodeWithTag("notificationsDeliveredBy").performClick()
        assertEquals(1, clicked)
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
