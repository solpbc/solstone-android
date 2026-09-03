// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.BackgroundRunner
import app.solstone.observer.harness.HarnessBacklogStatus
import app.solstone.observer.harness.HarnessPlStatus
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
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PhoneStatusViewModelTest {
    @Test
    fun initialLoadRunsOnceAndFirstResumeDoesNotRefresh() {
        val runner = ManualRunner()
        val poster = ManualPoster()
        val reads = mutableListOf<HarnessBacklogStatus>()
        val viewModel = viewModel(runner, poster) {
            HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 0, emptyList()).also(reads::add)
        }

        assertEquals(1, runner.pendingCount)
        viewModel.onHostResumed()
        assertEquals(1, runner.pendingCount)

        runner.runNext()
        poster.runNext()
        assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState)
        assertEquals(1, reads.size)

        viewModel.onHostResumed()
        assertEquals(1, runner.pendingCount)
    }

    @Test
    fun staleSuccessDoesNotPublishAfterNewerRefreshRequest() {
        val runner = ManualRunner()
        val poster = ManualPoster()
        val first = HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 1, emptyList())
        val second = HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 2, emptyList())
        val results = ArrayDeque<() -> HarnessBacklogStatus>(listOf({ first }, { second }))
        val viewModel = viewModel(runner, poster) { results.removeFirst().invoke() }

        viewModel.refresh()
        runner.runNext()
        poster.runNext()

        assertIs<LoadState.Loading>(viewModel.statusState)
        assertEquals(1, runner.pendingCount)

        runner.runNext()
        poster.runNext()
        val loaded = assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState)
        assertEquals(2, loaded.value.status.pendingCount)
    }

    @Test
    fun staleFailureDoesNotPublishAfterNewerRefreshRequest() {
        val runner = ManualRunner()
        val poster = ManualPoster()
        val boom = IllegalStateException("old read failed")
        val second = HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 3, emptyList())
        val results = ArrayDeque<() -> HarnessBacklogStatus>(listOf({ throw boom }, { second }))
        val viewModel = viewModel(runner, poster) { results.removeFirst().invoke() }

        viewModel.refresh()
        runner.runNext()
        poster.runNext()

        assertIs<LoadState.Loading>(viewModel.statusState)
        assertEquals(1, runner.pendingCount)

        runner.runNext()
        poster.runNext()
        val loaded = assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState)
        assertEquals(3, loaded.value.status.pendingCount)
    }

    @Test
    fun repeatedRefreshDuringFlightCollapsesToOneTrailingRead() {
        val runner = ManualRunner()
        val poster = ManualPoster()
        val viewModel = viewModel(runner, poster) {
            HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 0, emptyList())
        }

        viewModel.refresh()
        viewModel.refresh()
        viewModel.refresh()
        assertEquals(1, runner.pendingCount)

        runner.runNext()
        poster.runNext()
        assertEquals(1, runner.pendingCount)
    }

    private fun viewModel(
        runner: ManualRunner,
        poster: ManualPoster,
        read: () -> HarnessBacklogStatus,
    ): PhoneStatusViewModel = PhoneStatusViewModel(
        read = read,
        sources = TestSourcesReader,
        asyncLoad = AsyncLoad(runner, poster),
    )
}

private class ManualRunner : BackgroundRunner {
    private val tasks = ArrayDeque<() -> Unit>()

    val pendingCount: Int get() = tasks.size

    override fun submit(task: () -> Unit) {
        tasks.addLast(task)
    }

    fun runNext() {
        tasks.removeFirst().invoke()
    }
}

private class ManualPoster : MainPoster {
    private val tasks = ArrayDeque<() -> Unit>()

    override fun post(task: () -> Unit) {
        tasks.addLast(task)
    }

    fun runNext() {
        tasks.removeFirst().invoke()
    }
}

private object TestSourcesReader : SourcesReader {
    private val snapshot = SourcesReadModel(
        observer = ObserverStatus(SourceState.OFF, ReasonCode.NONE),
        sources = emptyList(),
    )

    override fun snapshot(): SourcesReadModel = snapshot

    override fun setWish(sourceId: String, wish: SourceWish): SourceToggleResult = SourceToggleResult.Applied

    override fun subscribe(listener: SourcesChangeListener): SourcesSubscription = SourcesSubscription {}
}
