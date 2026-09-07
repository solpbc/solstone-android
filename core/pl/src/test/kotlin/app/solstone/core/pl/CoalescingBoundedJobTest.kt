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
}
