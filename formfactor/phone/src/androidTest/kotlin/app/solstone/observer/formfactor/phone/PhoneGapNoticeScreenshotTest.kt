// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneGapNoticeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fourStatusFrames() {
        val now = System.currentTimeMillis()
        val clock = PhoneStatusNow { now }
        val gap = PhoneStatusModel(
            paired = true,
            online = true,
            pendingCount = 0,
            hasContentPending = false,
            awaitingMarkConfirmation = false,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
            gapLosses = listOf(PhoneGapLoss("loss", now, now, false)),
            gateOpen = true,
        )
        val caughtUp = gap.copy(gapLosses = emptyList())
        val unknown = gap.copy(
            gapLosses = emptyList(),
            unknownPresent = true,
            unknownUnseen = true,
            unseenUnknownKeys = listOf("draft/leaf"),
        )
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        var shot by mutableStateOf<@Composable () -> Unit>({})
        composeRule.setContent {
            CompositionLocalProvider(LocalPhoneStatusNow provides clock) {
                PhoneTheme {
                    Column(
                        Modifier
                            .testTag("gapShot")
                            .width(360.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(16.dp),
                    ) {
                        shot()
                    }
                }
            }
        }

        fun capture(name: String, content: @Composable () -> Unit) {
            composeRule.runOnIdle { shot = content }
            composeRule.waitForIdle()
            val image = composeRule.onNodeWithTag("gapShot").captureToImage()
            FileOutputStream(File(dir, name)).use { out ->
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }

        capture("gap-unseen-pill-and-pane.png") {
            Column(Modifier.fillMaxWidth()) {
                PhoneStatusPill(model = gap, onClick = {})
                Spacer(Modifier.height(12.dp))
                PhonePairedStatusContent(gap, emptyList(), { _ -> }, {})
            }
        }
        assertTextShown("there's a gap in your journal")
        assertTextShown("some of what you shared today didn't reach it.")
        composeRule.onNodeWithText("all caught up").assertDoesNotExist()

        capture("gap-pane-caught-up.png") {
            PhonePairedStatusContent(caughtUp, emptyList(), { _ -> }, {})
        }
        assertTextShown("all caught up")
        composeRule.onNodeWithText("there's a gap in your journal").assertDoesNotExist()

        capture("gap-unknown-outcome.png") {
            PhonePairedStatusContent(unknown, emptyList(), { _ -> }, {})
        }
        assertTextShown("an unfinished item couldn't be checked")
        assertTextShown("on this device")
        composeRule.onNodeWithText("all caught up").assertDoesNotExist()

        capture("gap-unknown-seen.png") {
            PhonePairedStatusContent(unknown.copy(unknownUnseen = false, unseenUnknownKeys = emptyList()), emptyList(), { _ -> }, {})
        }
        composeRule.onNodeWithText("an unfinished item couldn't be checked").assertDoesNotExist()
        composeRule.onNodeWithText("everything is in your journal").assertDoesNotExist()

        capture("gap-caught-up.png") {
            Column(Modifier.fillMaxWidth()) {
                PhoneStatusPill(model = caughtUp, onClick = {})
                Spacer(Modifier.height(12.dp))
                PhonePairedStatusContent(caughtUp, emptyList(), { _ -> }, {})
            }
        }
        assertTextShown("all caught up")
        assertTextShown("everything is in your journal")
        composeRule.onNodeWithText("an unfinished item couldn't be checked").assertDoesNotExist()
    }

    private fun assertTextShown(text: String) {
        composeRule.onAllNodesWithText(text)[0].assertIsDisplayed()
    }
}
