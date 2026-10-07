// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.platform.work.JournalRevokeOutcome
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

class LeaveJournalTest {

    private val cleared = GraphMutationResult.Cleared(PairingGraphSnapshot.Absent(1))
    private val conflict = GraphMutationResult.Conflict("left")

    @Test
    fun leaveJournalRunsOnDispatcherAndPreservesResultAndLogsOutcome() {
        for (outcome in JournalRevokeOutcome.entries) {
            for (expectedMutation in listOf(cleared, conflict)) {
                val caller = Thread.currentThread()
                val executor = Executors.newSingleThreadExecutor()
                val dispatcher = executor.asCoroutineDispatcher()
                try {
                    val executorThread = executor.submit<Thread> { Thread.currentThread() }.get()
                    val trace = mutableListOf<String>()
                    val threads = mutableListOf<Thread>()

                    val result = runBlocking {
                        leaveJournal(
                            dispatcher = dispatcher,
                            revoke = {
                                trace.add("revoke")
                                threads.add(Thread.currentThread())
                                outcome
                            },
                            forget = {
                                trace.add("forget")
                                threads.add(Thread.currentThread())
                                expectedMutation
                            },
                            log = { line ->
                                trace.add("log:$line")
                                threads.add(Thread.currentThread())
                            },
                        )
                    }

                    assertSame(expectedMutation, result)
                    assertEquals(
                        listOf("revoke", "log:kind=unpair revoke=${outcome.name.lowercase()}", "forget"),
                        trace,
                    )
                    assertEquals(3, threads.size)
                    for (t in threads) {
                        assertEquals(executorThread, t)
                        assertTrue(t != caller)
                    }
                } finally {
                    dispatcher.close()
                    executor.shutdown()
                }
            }
        }
    }

    @Test
    fun leaveJournalCatchesRevokeThrowAndLogsUnreachedAndReturnsForgetResult() {
        val caller = Thread.currentThread()
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val executorThread = executor.submit<Thread> { Thread.currentThread() }.get()
            val trace = mutableListOf<String>()
            val threads = mutableListOf<Thread>()

            val result = runBlocking {
                leaveJournal(
                    dispatcher = dispatcher,
                    revoke = {
                        trace.add("revoke")
                        threads.add(Thread.currentThread())
                        throw IllegalStateException("down")
                    },
                    forget = {
                        trace.add("forget")
                        threads.add(Thread.currentThread())
                        cleared
                    },
                    log = { line ->
                        trace.add("log:$line")
                        threads.add(Thread.currentThread())
                    },
                )
            }

            assertSame(cleared, result)
            assertEquals(
                listOf("revoke", "log:kind=unpair revoke=unreached", "forget"),
                trace,
            )
            assertEquals(3, threads.size)
            for (t in threads) {
                assertEquals(executorThread, t)
                assertTrue(t != caller)
            }
        } finally {
            dispatcher.close()
            executor.shutdown()
        }
    }
}
