// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.BackgroundRunner
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.MainPoster
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.SourceToggleResult
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesChangeListener
import app.solstone.observer.harness.SourcesReadModel
import app.solstone.observer.harness.SourcesReader
import app.solstone.observer.harness.SourcesSubscription
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourcesViewModelTest {
    @Test
    fun throwingSnapshotSurfacesAsFailed() {
        val boom = RuntimeException("snapshot failed")
        val viewModel = SourcesViewModel(
            sources = FakeSourcesReader { throw boom },
            asyncLoad = immediateAsyncLoad(),
        )

        val failed = assertIs<LoadState.Failed>(viewModel.sourcesState)
        assertSame(boom, failed.error)
        assertTrue(viewModel.sourcesState !is LoadState.Loaded)
    }

    @Test
    fun healthySnapshotLoads() {
        val model = SourcesReadModel(
            observer = ObserverStatus(SourceState.OFF, ReasonCode.NONE),
            sources = emptyList(),
        )
        val viewModel = SourcesViewModel(
            sources = FakeSourcesReader { model },
            asyncLoad = immediateAsyncLoad(),
        )

        val loaded = assertIs<LoadState.Loaded<SourcesReadModel>>(viewModel.sourcesState)
        assertSame(model, loaded.value)
        assertTrue(viewModel.sourcesState !is LoadState.Failed)
    }
}

private fun immediateAsyncLoad(): AsyncLoad =
    AsyncLoad(
        background = BackgroundRunner { it() },
        main = MainPoster { it() },
    )

private class FakeSourcesReader(
    private val snapshotFn: () -> SourcesReadModel,
) : SourcesReader {
    override fun snapshot(): SourcesReadModel = snapshotFn()

    override fun setWish(sourceId: String, wish: SourceWish): SourceToggleResult =
        SourceToggleResult.Applied

    override fun subscribe(listener: SourcesChangeListener): SourcesSubscription =
        SourcesSubscription {}
}
