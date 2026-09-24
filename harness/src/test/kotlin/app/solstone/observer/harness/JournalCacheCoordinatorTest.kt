// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JournalCacheCoordinatorTest {
    @Test
    fun recoveryGatesStartupAndPassRunsWhenRequestedAfterward() {
        var recovered = false
        var passes = 0
        val tasks = ArrayDeque<() -> Unit>()
        val coordinator = JournalCacheCoordinator(
            canRun = { recovered },
            submit = { tasks.addLast(it); true },
            monotonicElapsedMs = { 1_000L },
            runPass = { passes += 1 },
        )

        coordinator.requestImmediatePass()
        assertTrue(tasks.isEmpty())
        assertEquals(0, passes)

        recovered = true
        coordinator.requestImmediatePass()
        assertEquals(1, tasks.size)

        tasks.removeFirst().invoke()
        assertEquals(1, passes)
    }

    @Test
    fun routinePassRunsAtMostOncePerFifteenMinutes() {
        var monotonic = 1_000L
        var passes = 0
        val tasks = ArrayDeque<() -> Unit>()
        val coordinator = JournalCacheCoordinator(
            canRun = { true },
            submit = { tasks.addLast(it); true },
            monotonicElapsedMs = { monotonic },
            runPass = { passes += 1 },
        )

        coordinator.requestRoutinePass()
        assertEquals(1, tasks.size)
        tasks.removeFirst().invoke()
        assertEquals(1, passes)

        // Within 15 minutes, no new pass is queued
        monotonic += 10L * 60L * 1000L
        coordinator.requestRoutinePass()
        assertTrue(tasks.isEmpty())

        // After 15 minutes, a routine pass runs
        monotonic += 5L * 60L * 1000L
        coordinator.requestRoutinePass()
        assertEquals(1, tasks.size)
        tasks.removeFirst().invoke()
        assertEquals(2, passes)
    }

    @Test
    fun secondRequestWhilePassQueuedDoesNotStartSecondPass() {
        var passes = 0
        val tasks = ArrayDeque<() -> Unit>()
        val coordinator = JournalCacheCoordinator(
            canRun = { true },
            submit = { tasks.addLast(it); true },
            monotonicElapsedMs = { 1_000L },
            runPass = { passes += 1 },
        )

        coordinator.requestImmediatePass()
        coordinator.requestImmediatePass()
        coordinator.requestRoutinePass()
        assertEquals(1, tasks.size)

        tasks.removeFirst().invoke()
        assertEquals(1, passes)
    }

    @Test
    fun closePreventsQueuedAndFuturePasses() {
        val tasks = ArrayDeque<() -> Unit>()
        var passes = 0
        val coordinator = JournalCacheCoordinator(
            canRun = { true },
            submit = { tasks.addLast(it); true },
            monotonicElapsedMs = { 1_000L },
            runPass = { passes += 1 },
        )

        coordinator.requestImmediatePass()
        coordinator.close()
        tasks.removeFirst().invoke()
        assertEquals(0, passes)

        coordinator.requestImmediatePass()
        coordinator.requestRoutinePass()
        assertTrue(tasks.isEmpty())
    }
}
