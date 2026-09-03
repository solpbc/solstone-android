// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.HarnessBacklogStatus
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.SourcesReader

class PhoneStatusViewModel(
    private val read: () -> HarnessBacklogStatus,
    private val sources: SourcesReader,
    private val asyncLoad: AsyncLoad,
    private val capturedStatusState: LoadState<PhoneStatusSnapshot>? = null,
) : ViewModel() {
    var statusState: LoadState<PhoneStatusSnapshot> by mutableStateOf(LoadState.Loading)
        private set

    private var requestedGeneration = 0L
    private var readInFlight = false
    private var trailingRefreshRequested = false
    private var hasSeenHostResume = false

    init {
        refresh()
    }

    fun refresh() {
        val generation = ++requestedGeneration
        val captured = capturedStatusState
        if (captured != null) {
            statusState = captured
            return
        }
        if (readInFlight) {
            trailingRefreshRequested = true
            return
        }
        startRead(generation)
    }

    fun onHostResumed() {
        if (hasSeenHostResume) {
            refresh()
        } else {
            hasSeenHostResume = true
        }
    }

    private fun startRead(generation: Long) {
        readInFlight = true
        asyncLoad.load({ phoneStatusSnapshotOf(read(), sources.snapshot().sources) }) { incoming ->
            if (incoming is LoadState.Loading) {
                if (generation == requestedGeneration) statusState = incoming
                return@load
            }
            readInFlight = false
            if (generation == requestedGeneration) statusState = incoming
            if (trailingRefreshRequested) {
                trailingRefreshRequested = false
                startRead(requestedGeneration)
            }
        }
    }
}
