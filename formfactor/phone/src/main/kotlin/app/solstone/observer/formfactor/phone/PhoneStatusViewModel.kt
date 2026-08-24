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
) : ViewModel() {
    var statusState: LoadState<PhoneStatusSnapshot> by mutableStateOf(LoadState.Loading)
        private set

    init {
        asyncLoad.load({ phoneStatusSnapshotOf(read(), sources.snapshot().sources) }) { statusState = it }
    }
}
