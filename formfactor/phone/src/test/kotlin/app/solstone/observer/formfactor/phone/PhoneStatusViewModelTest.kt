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
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
    fun aBackgroundStatusReplacesTheCountWithoutReadingAgainOrShowingLoading() {
        val runner = ManualRunner()
        val poster = ManualPoster()
        var reads = 0
        val viewModel = viewModel(runner, poster) {
            reads += 1
            HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 6, emptyList())
        }
        runner.runNext()
        poster.runNext()

        viewModel.publish(HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 1, emptyList()))

        assertEquals(6, assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState).value.status.pendingCount)
        runner.runNext()
        poster.runNext()
        assertEquals(1, assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState).value.status.pendingCount)
        assertEquals(1, reads)
    }

    @Test
    fun staleSuccessDoesNotPublishAfterNewerRefreshRequest() {
        val runner = ManualRunner()
        val poster = ManualPoster()
        val first = HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 1, emptyList())
        val second = HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 2, emptyList())
        val results = ArrayDeque<() -> HarnessBacklogStatus>(listOf({ first }, { second }))
        var reads = 0
        val viewModel = viewModel(runner, poster) {
            reads += 1
            results.removeFirst().invoke()
        }

        viewModel.refresh()
        runner.runNext()
        poster.runNext()

        assertIs<LoadState.Loading>(viewModel.statusState)
        assertEquals(1, runner.pendingCount)

        runner.runNext()
        poster.runNext()
        val loaded = assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState)
        assertEquals(2, loaded.value.status.pendingCount)
        assertEquals(2, reads)
    }

    @Test
    fun staleFailureDoesNotPublishAfterNewerRefreshRequest() {
        val runner = ManualRunner()
        val poster = ManualPoster()
        val boom = IllegalStateException("old read failed")
        val second = HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 3, emptyList())
        val results = ArrayDeque<() -> HarnessBacklogStatus>(listOf({ throw boom }, { second }))
        var reads = 0
        val viewModel = viewModel(runner, poster) {
            reads += 1
            results.removeFirst().invoke()
        }

        viewModel.refresh()
        runner.runNext()
        poster.runNext()

        assertIs<LoadState.Loading>(viewModel.statusState)
        assertEquals(1, runner.pendingCount)

        runner.runNext()
        poster.runNext()
        val loaded = assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState)
        assertEquals(3, loaded.value.status.pendingCount)
        assertEquals(2, reads)
    }

    @Test
    fun staleSuccessDoesNotPublishAfterNewerFailure() {
        val runner = ManualRunner()
        val poster = ManualPoster()
        val first = HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 1, emptyList())
        val newerFailure = IllegalStateException("newer read failed")
        val results = ArrayDeque<() -> HarnessBacklogStatus>(listOf({ first }, { throw newerFailure }))
        var reads = 0
        val viewModel = viewModel(runner, poster) {
            reads += 1
            results.removeFirst().invoke()
        }

        viewModel.refresh()
        runner.runNext()
        poster.runNext()

        assertIs<LoadState.Loading>(viewModel.statusState)
        assertEquals(1, runner.pendingCount)

        runner.runNext()
        poster.runNext()
        val failed = assertIs<LoadState.Failed>(viewModel.statusState)
        assertEquals(newerFailure, failed.error)
        assertEquals(2, reads)
    }

    @Test
    fun repeatedRefreshDuringFlightCollapsesToOneTrailingRead() {
        val runner = ManualRunner()
        val poster = ManualPoster()
        var reads = 0
        val viewModel = viewModel(runner, poster) {
            reads += 1
            HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 0, emptyList())
        }

        viewModel.refresh()
        viewModel.refresh()
        viewModel.refresh()
        assertEquals(1, runner.pendingCount)

        runner.runNext()
        poster.runNext()
        assertEquals(1, runner.pendingCount)

        runner.runNext()
        poster.runNext()
        assertEquals(0, runner.pendingCount)
        assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState)
        assertEquals(2, reads)
    }

    @Test
    fun publishAndRefreshBothReadAwaitingMarkConfirmation() {
        val runner = ManualRunner()
        val poster = ManualPoster()
        var awaiting = true
        val viewModel = viewModel(runner, poster, awaitingMarkConfirmation = { awaiting }) {
            HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 2, emptyList())
        }
        runner.runNext()
        poster.runNext()
        val initial = assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState)
        kotlin.test.assertTrue(initial.value.status.awaitingMarkConfirmation)

        viewModel.publish(HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 3, emptyList()))
        runner.runNext()
        poster.runNext()
        val published = assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState)
        kotlin.test.assertTrue(published.value.status.awaitingMarkConfirmation)

        awaiting = false
        viewModel.publish(HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 4, emptyList()))
        runner.runNext()
        poster.runNext()
        val publishedFalse = assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState)
        kotlin.test.assertFalse(publishedFalse.value.status.awaitingMarkConfirmation)
    }

    @Test
    fun publishAndRefreshBothReadCustodyAndRecoveryFacts() {
        val runner = ManualRunner()
        val poster = ManualPoster()
        var recovering = false
        var custody = true
        var gaps = PhoneGapStatus(
            losses = listOf(PhoneGapLoss("loss", 1L, 1L, false)),
            unknownPresent = true,
            unknownUnseen = true,
            unseenUnknownKeys = listOf("draft/leaf"),
            gateOpen = true,
        )
        val viewModel = PhoneStatusViewModel(
            read = { HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 1, emptyList()) },
            sources = TestSourcesReader,
            asyncLoad = AsyncLoad(runner, poster),
            awaitingMarkConfirmation = { false },
            recoveryCompleted = { recovering },
            audioAwaitingCustody = { custody },
            gapStatus = { gaps },
        )
        runner.runNext()
        poster.runNext()
        val initial = assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState)
        assertFalse(initial.value.status.recoveryCompleted)
        assertTrue(initial.value.status.audioAwaitingCustody)
        assertEquals(listOf("loss"), initial.value.status.gapLosses.map { it.id })
        assertTrue(initial.value.status.gateOpen)

        recovering = true
        custody = false
        gaps = PhoneGapStatus()
        viewModel.publish(HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 2, emptyList()))
        runner.runNext()
        poster.runNext()
        val published = assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState)
        assertTrue(published.value.status.recoveryCompleted)
        assertFalse(published.value.status.audioAwaitingCustody)
        assertTrue(published.value.status.gapLosses.isEmpty())
        assertFalse(published.value.status.gateOpen)
    }

    @Test
    fun visitMarksExactlyTheDisplayedIdsAndKeepsThemOnThePane() {
        val now = 1_762_000_000_000L
        val zone = ZoneId.of("UTC")
        val shown = PhoneGapLoss("shown", now, now, false)
        val later = PhoneGapLoss("later", now, now, false)
        var gaps = PhoneGapStatus(
            losses = listOf(shown),
            unknownPresent = true,
            unknownUnseen = true,
            unseenUnknownKeys = listOf("draft/leaf"),
            gateOpen = true,
        )
        val marked = mutableListOf<Pair<Set<String>, Set<String>>>()
        var seenCalls = 0
        val runner = ManualRunner()
        val poster = ManualPoster()
        val viewModel = PhoneStatusViewModel(
            read = { HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 0, emptyList()) },
            sources = TestSourcesReader,
            asyncLoad = AsyncLoad(runner, poster),
            awaitingMarkConfirmation = { false },
            recoveryCompleted = { true },
            audioAwaitingCustody = { false },
            gapStatus = { gaps },
            markSeen = { ids, keys ->
                marked += ids to keys
                gaps = gaps.copy(
                    losses = gaps.losses.map { loss -> if (loss.id in ids) loss.copy(seen = true) else loss },
                    unseenUnknownKeys = gaps.unseenUnknownKeys.filter { it !in keys },
                    unknownUnseen = gaps.unseenUnknownKeys.any { it !in keys },
                )
                true
            },
            onGapSeen = { seenCalls += 1 },
            zone = zone,
        )
        viewModel.onStatusForeground(true)
        assertTrue(marked.isEmpty())
        runner.runNext()
        poster.runNext()
        viewModel.onNow(now)
        assertEquals(0, runner.pendingCount)
        viewModel.onStatusForeground(true)
        assertEquals(listOf(setOf("shown") to setOf("draft/leaf")), marked)
        assertEquals(1, seenCalls)
        val pill = assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState).value.status
        assertTrue(pill.gapLosses.single { it.id == "shown" }.seen)
        assertFalse(pill.unknownUnseen)
        val pane = assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.paneState()).value.status
        assertFalse(pane.gapLosses.single { it.id == "shown" }.seen)

        gaps = gaps.copy(losses = gaps.losses + later)
        viewModel.refresh()
        runner.runNext()
        poster.runNext()
        viewModel.onStatusForeground(false)
        viewModel.onHostStop(changingConfigurations = true)
        viewModel.onStatusForeground(true)
        assertEquals(1, marked.size)
        val reloaded = assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState).value.status
        assertTrue(reloaded.gapLosses.single { it.id == "shown" }.seen)
        assertFalse(reloaded.gapLosses.single { it.id == "later" }.seen)
        val during = assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.paneState()).value.status
        assertFalse(during.gapLosses.single { it.id == "shown" }.seen)
        assertFalse(during.gapLosses.single { it.id == "later" }.seen)

        viewModel.onHostStop(changingConfigurations = false)
        val ended = assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.paneState()).value.status
        assertTrue(ended.gapLosses.single { it.id == "shown" }.seen)
        viewModel.onStatusForeground(true)
        assertEquals(setOf("later"), marked.last().first)
        assertEquals(emptySet(), marked.last().second)
    }

    @Test
    fun aVisitBeforeTheGateMarksNothing() {
        val now = 1_762_000_000_000L
        var open = false
        val gaps = {
            PhoneGapStatus(
                losses = listOf(PhoneGapLoss("loss", now, now, false)),
                gateOpen = open,
            )
        }
        val marked = mutableListOf<Set<String>>()
        val runner = ManualRunner()
        val poster = ManualPoster()
        val viewModel = PhoneStatusViewModel(
            read = { HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 0, emptyList()) },
            sources = TestSourcesReader,
            asyncLoad = AsyncLoad(runner, poster),
            awaitingMarkConfirmation = { false },
            recoveryCompleted = { true },
            audioAwaitingCustody = { false },
            gapStatus = gaps,
            markSeen = { ids, _ -> marked += ids; true },
            zone = ZoneId.of("UTC"),
        )
        runner.runNext()
        poster.runNext()
        viewModel.onNow(now)
        viewModel.onStatusForeground(true)
        assertTrue(marked.isEmpty())
        open = true
        viewModel.refresh()
        runner.runNext()
        poster.runNext()
        viewModel.onStatusForeground(true)
        assertTrue(marked.isEmpty())
        viewModel.onHostStop(false)
        viewModel.onStatusForeground(true)
        assertEquals(listOf(setOf("loss")), marked)
    }

    @Test
    fun aFailedSeenWriteLeavesTheLossUnseen() {
        val now = 1_762_000_000_000L
        var seenCalls = 0
        val runner = ManualRunner()
        val poster = ManualPoster()
        val viewModel = PhoneStatusViewModel(
            read = { HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 0, emptyList()) },
            sources = TestSourcesReader,
            asyncLoad = AsyncLoad(runner, poster),
            awaitingMarkConfirmation = { false },
            recoveryCompleted = { true },
            audioAwaitingCustody = { false },
            gapStatus = {
                PhoneGapStatus(
                    losses = listOf(PhoneGapLoss("loss", now, now, false)),
                    gateOpen = true,
                )
            },
            markSeen = { _, _ -> false },
            onGapSeen = { seenCalls += 1 },
            zone = ZoneId.of("UTC"),
        )
        runner.runNext()
        poster.runNext()
        viewModel.onNow(now)
        viewModel.onStatusForeground(true)
        assertEquals(0, seenCalls)
        val status = assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState).value.status
        assertFalse(status.gapLosses.single().seen)
        viewModel.onHostStop(false)
        viewModel.onStatusForeground(true)
        val again = assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(viewModel.statusState).value.status
        assertFalse(again.gapLosses.single().seen)
    }

    @Test
    fun onClockChangesNowWithoutReadingAgain() {
        val runner = ManualRunner()
        val poster = ManualPoster()
        var reads = 0
        val viewModel = viewModel(runner, poster) {
            reads += 1
            HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 0, emptyList())
        }
        runner.runNext()
        poster.runNext()
        assertEquals(1, reads)
        viewModel.onNow(42L)
        assertEquals(42L, viewModel.nowEpochMs)
        assertEquals(1, reads)
        assertEquals(0, runner.pendingCount)
    }

    @Test
    fun aSeenLossSurvivesANewVisitAndTheWidePaneCountsAsOne() {
        val now = 1_762_000_000_000L
        val marked = mutableListOf<Set<String>>()
        val runner = ManualRunner()
        val poster = ManualPoster()
        val viewModel = PhoneStatusViewModel(
            read = { HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 0, emptyList()) },
            sources = TestSourcesReader,
            asyncLoad = AsyncLoad(runner, poster),
            awaitingMarkConfirmation = { false },
            recoveryCompleted = { true },
            audioAwaitingCustody = { false },
            gapStatus = {
                PhoneGapStatus(
                    losses = listOf(PhoneGapLoss("loss", now, now, seen = true)),
                    gateOpen = true,
                )
            },
            markSeen = { ids, _ -> marked += ids; true },
            zone = ZoneId.of("UTC"),
        )
        runner.runNext()
        poster.runNext()
        viewModel.onNow(now)
        viewModel.onStatusForeground(true)
        assertTrue(marked.isEmpty())
        assertTrue(phoneStatusContentVisible(sheetOpen = false, pairingOpen = false, wide = true, pairedDetail = true))
        assertFalse(phoneStatusContentVisible(sheetOpen = false, pairingOpen = false, wide = false, pairedDetail = true))
        assertTrue(phoneStatusContentVisible(sheetOpen = true, pairingOpen = false, wide = false, pairedDetail = false))
        assertFalse(phoneStatusContentVisible(sheetOpen = true, pairingOpen = true, wide = true, pairedDetail = true))
    }

    private fun viewModel(
        runner: ManualRunner,
        poster: ManualPoster,
        awaitingMarkConfirmation: () -> Boolean = { false },
        read: () -> HarnessBacklogStatus,
    ): PhoneStatusViewModel = PhoneStatusViewModel(
        read = read,
        sources = TestSourcesReader,
        asyncLoad = AsyncLoad(runner, poster),
        awaitingMarkConfirmation = awaitingMarkConfirmation,
        recoveryCompleted = { true },
        audioAwaitingCustody = { false },
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
