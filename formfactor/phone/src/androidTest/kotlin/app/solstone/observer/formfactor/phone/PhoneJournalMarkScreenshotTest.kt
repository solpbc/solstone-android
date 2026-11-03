// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.pl.journalMarkFromJid
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Frames of the mark's real geometry, computed marks included, to look at beside the journal's. */
@RunWith(AndroidJUnit4::class)
class PhoneJournalMarkScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun markFrames() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        val rotated = JournalMarkPresentation.Identified(journalMarkFromJid("f30ed159-ef46-8e9c-913f-e49f0fe7d201"))
        val hyphenated = JournalMarkPresentation.Identified(journalMarkFromJid("9661def7-be18-8d3e-a180-0000000001c5"))
        var shot by mutableStateOf<@Composable () -> Unit>({})
        composeRule.setContent {
            PhoneTheme {
                Column(
                    Modifier
                        .testTag("markShot")
                        .width(360.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                ) {
                    shot()
                }
            }
        }

        fun capture(name: String, content: @Composable () -> Unit) {
            composeRule.runOnIdle { shot = content }
            composeRule.waitForIdle()
            val image = composeRule.onNodeWithTag("markShot").captureToImage()
            FileOutputStream(File(dir, name)).use { out ->
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }

        capture("mark-cards.png") {
            JournalMarkCard(presentation = rotated)
            Spacer(Modifier.height(12.dp))
            JournalMarkCard(presentation = hyphenated)
            Spacer(Modifier.height(12.dp))
            JournalMarkCard(presentation = JournalMarkPresentation.Unavailable)
            Spacer(Modifier.height(12.dp))
            JournalMarkCard(presentation = JournalMarkPresentation.Unconfirmed)
        }
        capture("mark-pills.png") {
            PhoneJournalMarkPill(onClick = {}, paired = true, presentation = rotated)
            Spacer(Modifier.height(12.dp))
            PhoneJournalMarkPill(onClick = {}, paired = true, presentation = hyphenated)
            Spacer(Modifier.height(12.dp))
            PhoneJournalMarkPill(onClick = {}, paired = true, presentation = JournalMarkPresentation.Unavailable)
            Spacer(Modifier.height(12.dp))
            PhoneJournalMarkPill(onClick = {}, paired = true, presentation = JournalMarkPresentation.Unconfirmed)
        }
    }
}
