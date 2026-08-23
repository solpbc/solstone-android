// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.SourceToggleResult
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import app.solstone.observer.harness.SourcesReader

class SourcesViewModel(
    private val sources: SourcesReader,
    private val asyncLoad: AsyncLoad,
) : ViewModel() {
    var sourcesState: LoadState<SourcesReadModel> by mutableStateOf(LoadState.Loading)
        private set

    private val subscription = sources.subscribe { refresh() }

    init {
        refresh()
    }

    fun refresh() {
        asyncLoad.load({ sources.snapshot() }) { incoming ->
            val current = sourcesState
            sourcesState = if (incoming is LoadState.Loading && current is LoadState.Loaded) {
                current
            } else {
                incoming
            }
        }
    }

    fun setWish(sourceId: String, wish: SourceWish): SourceToggleResult =
        sources.setWish(sourceId, wish)

    override fun onCleared() {
        subscription.close()
        super.onCleared()
    }
}
