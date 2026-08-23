// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.BackgroundRunner
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.MainPoster
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceToggleResult
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesChangeListener
import app.solstone.observer.harness.SourcesReadModel
import app.solstone.observer.harness.SourcesReader
import app.solstone.observer.harness.SourcesSubscription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourcesViewModelComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sourcesStateRecompositionUpdatesTag() {
        val reader = MutableSourcesReader()
        val viewModel = SourcesViewModel(
            sources = reader,
            asyncLoad = AsyncLoad(
                background = BackgroundRunner { it() },
                main = MainPoster { it() },
            ),
        )

        composeRule.setContent {
            val label = when (val state = viewModel.sourcesState) {
                LoadState.Loading -> "loading"
                is LoadState.Loaded -> "loaded:${state.value.sources.size}"
                is LoadState.Failed -> "failed"
            }
            Text(text = label, modifier = Modifier.testTag("sourcesState"))
        }

        composeRule.onNodeWithTag("sourcesState").assertTextEquals("loaded:0")

        reader.model = SourcesReadModel(
            observer = ObserverStatus(SourceState.OFF, ReasonCode.NONE),
            sources = listOf(
                SourceStatus("audio", SourceWish.On, SourceState.SETTING_UP, ReasonCode.NONE),
            ),
        )
        reader.listener?.onSourcesChanged()

        composeRule.onNodeWithTag("sourcesState").assertTextEquals("loaded:1")
    }
}

private class MutableSourcesReader : SourcesReader {
    var model = SourcesReadModel(
        observer = ObserverStatus(SourceState.OFF, ReasonCode.NONE),
        sources = emptyList(),
    )
    var listener: SourcesChangeListener? = null

    override fun snapshot(): SourcesReadModel = model

    override fun setWish(sourceId: String, wish: SourceWish): SourceToggleResult =
        SourceToggleResult.Applied

    override fun subscribe(listener: SourcesChangeListener): SourcesSubscription {
        this.listener = listener
        return SourcesSubscription { this.listener = null }
    }
}
