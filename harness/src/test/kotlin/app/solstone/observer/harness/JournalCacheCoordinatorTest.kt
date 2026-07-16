// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.platform.persistence.room.JournalCacheEvictionResult
import app.solstone.platform.persistence.room.JournalCacheLimitSaveResult
import app.solstone.platform.persistence.room.JournalCacheSnapshot
import app.solstone.platform.persistence.room.ReclaimedCacheSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JournalCacheCoordinatorTest {
    @Test
    fun recoveryGatesStartupAndExactlyOneImmediatePassRunsAfterward() {
        var recovered = false
        var passes = 0
        val tasks = ArrayDeque<() -> Unit>()
        val coordinator = coordinator(
            canRun = { recovered },
            submit = { tasks.addLast(it); true },
            runPass = { passes += 1; result() },
        )

        coordinator.requestImmediatePass()
        assertTrue(tasks.isEmpty())
        recovered = true
        coordinator.requestImmediatePass()
        coordinator.requestImmediatePass()
        assertEquals(1, tasks.size)

        tasks.removeFirst().invoke()
        assertEquals(1, passes)
    }

    @Test
    fun routinePassesCoalesceAndRunAtMostOncePerFifteenMinutes() {
        var monotonic = 1_000L
        var passes = 0
        val tasks = ArrayDeque<() -> Unit>()
        val coordinator = coordinator(
            monotonic = { monotonic },
            submit = { tasks.addLast(it); true },
            runPass = { passes += 1; result() },
        )

        coordinator.requestRoutinePass()
        coordinator.requestRoutinePass()
        assertEquals(1, tasks.size)
        tasks.removeFirst().invoke()
        coordinator.requestRoutinePass()
        assertTrue(tasks.isEmpty())

        monotonic += JOURNAL_CACHE_ROUTINE_INTERVAL_MS
        coordinator.requestRoutinePass()
        assertEquals(1, tasks.size)
        tasks.removeFirst().invoke()
        assertEquals(2, passes)
    }

    @Test
    fun backwardWallClockCorrectionDoesNotDelayRoutinePassAndUsesCorrectedEpoch() {
        var monotonic = 1_000L
        var now = 10_000_000L
        val decidedAt = mutableListOf<Long>()
        val tasks = ArrayDeque<() -> Unit>()
        val coordinator = coordinator(
            monotonic = { monotonic },
            now = { now },
            submit = { tasks.addLast(it); true },
            runPass = { decidedAt += it; result() },
        )

        coordinator.requestRoutinePass()
        tasks.removeFirst().invoke()

        monotonic += JOURNAL_CACHE_ROUTINE_INTERVAL_MS
        now -= 60L * 60L * 1000L
        coordinator.requestRoutinePass()

        assertEquals(1, tasks.size)
        tasks.removeFirst().invoke()
        assertEquals(listOf(10_000_000L, 6_400_000L), decidedAt)
    }

    @Test
    fun confirmedSaveDuringPassDrainsSecondPassButRoutineRequestsDoNot() {
        val events = mutableListOf<String>()
        val tasks = ArrayDeque<() -> Unit>()
        var limit = 4_000_000_000L
        var passes = 0
        lateinit var coordinator: JournalCacheCoordinator
        coordinator = coordinator(
            snapshot = { JournalCacheSnapshot(limit, null) },
            save = { bytes ->
                events += "save:$bytes"
                limit = bytes
                JournalCacheLimitSaveResult.Saved(JournalCacheSnapshot(limit, null))
            },
            submit = { tasks.addLast(it); true },
            runPass = {
                passes += 1
                events += "pass:$limit"
                if (passes == 1) {
                    coordinator.requestRoutinePass()
                    coordinator.requestRoutinePass()
                    coordinator.saveLimit(8_000_000_000L)
                }
                result(limit)
            },
        )

        coordinator.requestRoutinePass()
        tasks.removeFirst().invoke()

        assertEquals(2, passes)
        assertEquals(listOf("pass:4000000000", "save:8000000000", "pass:8000000000"), events)
        assertEquals(8_000_000_000L, coordinator.state().configuredLimitBytes)
    }

    @Test
    fun rejectedAndFailedSavesKeepPriorValueAndQueueNoPass() {
        val tasks = ArrayDeque<() -> Unit>()
        var saveResult: JournalCacheLimitSaveResult = JournalCacheLimitSaveResult.Rejected(3L)
        val coordinator = coordinator(
            submit = { tasks.addLast(it); true },
            save = { saveResult },
        )

        assertEquals(HarnessJournalCacheSaveError.REJECTED, coordinator.saveLimit(3L).saveError)
        assertTrue(tasks.isEmpty())
        saveResult = JournalCacheLimitSaveResult.Failed(8L)
        val failed = coordinator.saveLimit(8L)
        assertEquals(HarnessJournalCacheSaveError.FAILED, failed.saveError)
        assertEquals(4_000_000_000L, failed.configuredLimitBytes)
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun closeAndRejectedSubmissionAreSafeNoOps() {
        var submissions = 0
        val coordinator = coordinator(submit = { submissions += 1; false })
        coordinator.requestImmediatePass()
        coordinator.requestImmediatePass()
        assertEquals(2, submissions)
        coordinator.close()
        coordinator.requestImmediatePass()
        coordinator.requestRoutinePass()
        assertEquals(2, submissions)

        val queued = ArrayDeque<() -> Unit>()
        var passes = 0
        val closingWithQueuedWork = coordinator(
            submit = { queued.addLast(it); true },
            runPass = { passes += 1; result() },
        )
        closingWithQueuedWork.requestImmediatePass()
        closingWithQueuedWork.close()
        queued.removeFirst().invoke()
        assertEquals(0, passes)
    }

    private fun coordinator(
        canRun: () -> Boolean = { true },
        submit: (() -> Unit) -> Boolean = { it(); true },
        monotonic: () -> Long = { 1_000L },
        now: () -> Long = { 1_000L },
        snapshot: () -> JournalCacheSnapshot = { JournalCacheSnapshot(4_000_000_000L, null) },
        save: (Long) -> JournalCacheLimitSaveResult = { JournalCacheLimitSaveResult.Saved(JournalCacheSnapshot(it, null)) },
        runPass: (Long) -> JournalCacheEvictionResult = { result() },
    ) = JournalCacheCoordinator(
        canRun = canRun,
        submit = submit,
        monotonicElapsedMs = monotonic,
        snapshot = snapshot,
        saveLimitToStore = save,
        nowEpochMs = now,
        runPass = runPass,
    )

    private companion object {
        fun result(limit: Long = 4_000_000_000L) = JournalCacheEvictionResult(
            measuredUsageBytes = 0L,
            measuredFreeBytes = 9_000_000_000L,
            configuredLimitBytes = limit,
            pressureRemains = false,
            durablyMarkedIds = emptyList(),
            reclaimedSpace = ReclaimedCacheSpace(emptyList()),
            retryableResidualIds = emptyList(),
            refusedPathIds = emptyList(),
            blockedReason = null,
        )
    }
}
