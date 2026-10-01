// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class CoalescingBoundedJobTest {

    @Test
    fun runsSingleJobSuccessfully() {
        val executor = Executors.newCachedThreadPool()
        val runner = CoalescingBoundedJob<String>("test-job", boundMillis = 2000L, executor = executor)
        val executed = CountDownLatch(1)
        val count = AtomicInteger(0)

        runner.submit("payload-1") { snap, gen ->
            assertEquals("payload-1", snap)
            count.incrementAndGet()
            executed.countDown()
        }

        executed.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        assertEquals(1, count.get())
    }

    @Test
    fun coalescesMultipleTriggersToSingleSubsequentRun() {
        val executor = Executors.newCachedThreadPool()
        val runner = CoalescingBoundedJob<Int>("test-job", boundMillis = 2000L, executor = executor)
        val firstStarted = CountDownLatch(1)
        val firstBlocker = CountDownLatch(1)
        val finalCompletion = CountDownLatch(2)
        val executedCount = AtomicInteger(0)
        val executedValues = mutableListOf<Int>()

        runner.submit(1) { value, _ ->
            synchronized(executedValues) { executedValues += value }
            executedCount.incrementAndGet()
            firstStarted.countDown()
            firstBlocker.await(3, TimeUnit.SECONDS)
            finalCompletion.countDown()
        }

        firstStarted.await(3, TimeUnit.SECONDS)

        // Submit multiple while first is in-flight
        for (i in 2..5) {
            runner.submit(i) { value, _ ->
                synchronized(executedValues) { executedValues += value }
                executedCount.incrementAndGet()
                finalCompletion.countDown()
            }
        }

        firstBlocker.countDown()
        finalCompletion.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        assertEquals(2, executedCount.get())
        synchronized(executedValues) {
            assertEquals(listOf(1, 5), executedValues)
        }
    }

    @Test
    fun bumpingGenerationInvalidatesInFlightCompletions() {
        val executor = Executors.newCachedThreadPool()
        val runner = CoalescingBoundedJob<Int>("test-job", boundMillis = 2000L, executor = executor)
        val started = CountDownLatch(1)
        val blocker = CountDownLatch(1)
        val completed = AtomicInteger(0)

        runner.submit(1) { _, gen ->
            started.countDown()
            blocker.await(3, TimeUnit.SECONDS)
            if (gen == runner.currentGeneration()) {
                completed.incrementAndGet()
            }
        }

        started.await(3, TimeUnit.SECONDS)
        runner.bumpGeneration()
        blocker.countDown()

        Thread.sleep(100)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        assertEquals(0, completed.get())
    }

    @Test
    fun timeoutFencesGenerationAndReleasesSlotForSubsequentSubmission() {
        val executor = Executors.newCachedThreadPool()
        val runner = CoalescingBoundedJob<Int>("test-job", boundMillis = 100L, executor = executor)
        val firstStarted = CountDownLatch(1)
        val firstUnblock = CountDownLatch(1)
        val secondExecuted = CountDownLatch(1)
        val persistedValues = mutableListOf<Int>()

        // Submit task 1 which hangs past boundMillis
        runner.submit(1) { value, gen ->
            firstStarted.countDown()
            firstUnblock.await(3, TimeUnit.SECONDS)
            if (gen == runner.currentGeneration()) {
                synchronized(persistedValues) { persistedValues += value }
            }
        }

        firstStarted.await(3, TimeUnit.SECONDS)
        // Wait for task 1 to timeout in drainLoop (>100ms)
        Thread.sleep(200)

        // Now submit task 2
        runner.submit(2) { value, gen ->
            if (gen == runner.currentGeneration()) {
                synchronized(persistedValues) { persistedValues += value }
            }
            secondExecuted.countDown()
        }

        secondExecuted.await(3, TimeUnit.SECONDS)

        // Late completion of task 1
        firstUnblock.countDown()
        Thread.sleep(100)

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        // Only task 2 should have persisted because task 1's generation was fenced on timeout
        synchronized(persistedValues) {
            assertEquals(listOf(2), persistedValues)
        }
    }

    @Test
    fun closeThenSubmitDoesNotRun() {
        val executor = Executors.newCachedThreadPool()
        val runner = CoalescingBoundedJob<String>("test-job", boundMillis = 2000L, executor = executor)
        val ran = AtomicInteger(0)

        runner.close()
        runner.submit("payload") { _, _ ->
            ran.incrementAndGet()
        }

        Thread.sleep(100)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        assertEquals(0, ran.get())
    }

    @Test
    fun drainerFinalizationRacingSuccessorStillRunsSuccessor() {
        val executor = Executors.newCachedThreadPool()
        val runner = CoalescingBoundedJob<Int>("test-job", boundMillis = 2000L, executor = executor)
        val firstLatch = CountDownLatch(1)
        val secondLatch = CountDownLatch(1)
        val ran = mutableListOf<Int>()

        runner.submit(1) { value, _ ->
            synchronized(ran) { ran += value }
            firstLatch.countDown()
        }

        firstLatch.await(3, TimeUnit.SECONDS)
        // Submit second task right as first completes/exits
        runner.submit(2) { value, _ ->
            synchronized(ran) { ran += value }
            secondLatch.countDown()
        }

        secondLatch.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        synchronized(ran) {
            assertEquals(listOf(1, 2), ran)
        }
    }

    @Test
    fun onJobEndedFiresOncePerDrainedSnapshotOnSuccessTimeoutAndThrow() {
        val executor = Executors.newCachedThreadPool()
        val events = mutableListOf<String>()
        val successLatch = CountDownLatch(1)
        val timeoutLatch = CountDownLatch(1)
        val throwLatch = CountDownLatch(1)

        val runner = CoalescingBoundedJob<String>(
            name = "test-job",
            boundMillis = 50L,
            executor = executor,
            onGiveUp = { snap, _ ->
                synchronized(events) { events += "giveUp:$snap" }
            },
            onJobEnded = { snap, _ ->
                synchronized(events) { events += "ended:$snap" }
                when (snap) {
                    "success" -> successLatch.countDown()
                    "timeout" -> timeoutLatch.countDown()
                    "throw" -> throwLatch.countDown()
                }
            },
        )

        runner.submit("success") { _, _ -> }
        kotlin.test.assertTrue(successLatch.await(3, TimeUnit.SECONDS))

        val hangLatch = CountDownLatch(1)
        runner.submit("timeout") { _, _ ->
            hangLatch.await(3, TimeUnit.SECONDS)
        }
        kotlin.test.assertTrue(timeoutLatch.await(3, TimeUnit.SECONDS))
        hangLatch.countDown()

        runner.submit("throw") { _, _ ->
            throw RuntimeException("task failed")
        }
        kotlin.test.assertTrue(throwLatch.await(3, TimeUnit.SECONDS))

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        synchronized(events) {
            assertEquals(
                listOf("ended:success", "giveUp:timeout", "ended:timeout", "ended:throw"),
                events,
            )
        }
    }

    @Test
    fun onJobEndedDoesNotFireForDroppedPendingSnapshotOnBumpGeneration() {
        val executor = Executors.newCachedThreadPool()
        val inFlightStarted = CountDownLatch(1)
        val inFlightBlocker = CountDownLatch(1)
        val inFlightEnded = CountDownLatch(1)
        val endedEvents = mutableListOf<String>()

        val runner = CoalescingBoundedJob<String>(
            name = "test-job",
            boundMillis = 2000L,
            executor = executor,
            onJobEnded = { snap, _ ->
                synchronized(endedEvents) { endedEvents += snap }
                if (snap == "inFlight") inFlightEnded.countDown()
            },
        )

        runner.submit("inFlight") { _, _ ->
            inFlightStarted.countDown()
            inFlightBlocker.await(3, TimeUnit.SECONDS)
        }
        kotlin.test.assertTrue(inFlightStarted.await(3, TimeUnit.SECONDS))

        // Submit pending while in-flight is running
        runner.submit("pendingToDrop") { _, _ -> }

        // Drop pending by bumping generation
        runner.bumpGeneration()

        // Release inFlight
        inFlightBlocker.countDown()
        kotlin.test.assertTrue(inFlightEnded.await(3, TimeUnit.SECONDS))

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        synchronized(endedEvents) {
            assertEquals(listOf("inFlight"), endedEvents)
        }
    }

    @Test
    fun throwingGiveUpOrJobEndedDoesNotBreakDrainOfPendingSnapshot() {
        val executor = Executors.newCachedThreadPool()
        val secondEnded = CountDownLatch(1)
        val endedEvents = mutableListOf<String>()

        // 1. Throwing onGiveUp
        val runner1 = CoalescingBoundedJob<String>(
            name = "test-job-giveup-throw",
            boundMillis = 50L,
            executor = executor,
            onGiveUp = { snap, _ ->
                if (snap == "timedOut") {
                    throw RuntimeException("giveUp throw")
                }
            },
            onJobEnded = { snap, _ ->
                synchronized(endedEvents) { endedEvents += snap }
                if (snap == "successor") secondEnded.countDown()
            },
        )

        val timedOutBlocker = CountDownLatch(1)
        runner1.submit("timedOut") { _, _ ->
            timedOutBlocker.await(3, TimeUnit.SECONDS)
        }
        // Submit successor while timedOut is waiting
        runner1.submit("successor") { _, _ -> }

        kotlin.test.assertTrue(secondEnded.await(3, TimeUnit.SECONDS))
        timedOutBlocker.countDown()
        runner1.close()

        synchronized(endedEvents) {
            assertEquals(listOf("timedOut", "successor"), endedEvents)
            endedEvents.clear()
        }

        // 2. Throwing onJobEnded
        val secondEnded2 = CountDownLatch(1)
        val firstStarted2 = CountDownLatch(1)
        val firstBlocker2 = CountDownLatch(1)

        val runner2 = CoalescingBoundedJob<String>(
            name = "test-job-ended-throw",
            boundMillis = 2000L,
            executor = executor,
            onJobEnded = { snap, _ ->
                synchronized(endedEvents) { endedEvents += snap }
                if (snap == "first") {
                    throw RuntimeException("jobEnded throw")
                } else if (snap == "second") {
                    secondEnded2.countDown()
                }
            },
        )

        runner2.submit("first") { _, _ ->
            firstStarted2.countDown()
            firstBlocker2.await(3, TimeUnit.SECONDS)
        }
        kotlin.test.assertTrue(firstStarted2.await(3, TimeUnit.SECONDS))

        runner2.submit("second") { _, _ -> }
        firstBlocker2.countDown()

        kotlin.test.assertTrue(secondEnded2.await(3, TimeUnit.SECONDS))
        runner2.close()

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        synchronized(endedEvents) {
            assertEquals(listOf("first", "second"), endedEvents)
        }
    }
}
