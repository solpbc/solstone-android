// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneDeckExitTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun deckOnlyBackFinishesActivityAtSplit() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(800.dp, 800.dp)),
            ) {
                PhoneObserverScreen(
                    loadState = LoadState.Loaded(
                        SourcesReadModel(
                            observer = ObserverStatus(SourceState.OFF, ReasonCode.NONE),
                            sources = emptyList(),
                        ),
                    ),
                    status = null,
                    onToggle = { _, _: SourceWish -> },
                    onStartObserving = {},
                )
            }
        }
        composeRule.onNodeWithTag("phoneDefaultDetail").assertIsDisplayed()
        val activity = composeRule.activity

        Espresso.pressBackUnconditionally()

        assertTrue(activity.isFinishing)
        ActivityScenario.launch(ComponentActivity::class.java)
    }
}
