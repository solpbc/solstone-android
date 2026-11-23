// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.i18n.phoneText
import app.solstone.core.pl.MigrationClient
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneMigrationPickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun duplicateModelRowsShowActivitySubLinesAndSelectDistinctDevices() {
        val now = Instant.parse("2026-11-23T12:00:00Z")
        val firstCid = "sha256:${"a".repeat(64)}"
        val secondCid = "sha256:${"b".repeat(64)}"
        val thirdCid = "sha256:${"c".repeat(64)}"
        val clients = listOf(
            MigrationClient(
                cid = firstCid,
                displayLabel = "samsung SM-A366E",
                reported = null,
                lastSeenAt = now.minusSeconds(2 * 86_400).toString(),
                pairedAt = now.minusSeconds(10 * 86_400).toString(),
            ),
            MigrationClient(
                cid = secondCid,
                displayLabel = "samsung SM-A366E",
                reported = null,
                lastSeenAt = null,
                pairedAt = now.minusSeconds(3 * 3_600).toString(),
            ),
            MigrationClient(
                cid = thirdCid,
                displayLabel = "Pixel 9",
                reported = null,
                lastSeenAt = null,
                pairedAt = null,
            ),
        )
        val selected = mutableListOf<String>()

        composeRule.setContent {
            PhoneTheme {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text(phoneText("android.migration.choose.device")) },
                    text = {
                        PhoneMigrationDeviceList(
                            clients = clients,
                            nowEpochMs = now.toEpochMilli(),
                            onSelect = { selected += it.cid },
                        )
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = {}) { Text(phoneText("android.phone.cancel")) }
                    },
                )
            }
        }

        val firstSub = phoneText(
            "android.migration.device.last.reached",
            "relative" to phoneText("android.about.freshnessdays", "count" to 2L),
        )
        val secondSub = phoneText(
            "android.migration.device.paired",
            "relative" to phoneText("android.about.freshnesshours", "count" to 3L),
        )

        val buttonRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        composeRule.onNodeWithText(firstSub, substring = true)
            .assertIsDisplayed()
            .assert(buttonRole)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(secondSub, substring = true)
            .assertIsDisplayed()
            .assert(buttonRole)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithText("Pixel 9")
            .assertIsDisplayed()
            .assert(buttonRole)
            .assertHeightIsAtLeast(48.dp)

        assertEquals(listOf(secondCid), selected)
    }
}
