// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class CoalescingBoundedJob<T>(
    private val name: String,
    private val boundMillis: Long = 15_000L,
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, name).apply { isDaemon = true }
    },
) : Closeable {
    private val lock = Any()
    private val generation = AtomicLong(0)
    private var inFlight = false
    private var latestPending: T? = null
    private var hasPending = false
    private var closed = false
    private var activeFuture: java.util.concurrent.Future<*>? = null

    fun bumpGeneration(): Long = synchronized(lock) {
        val gen = generation.incrementAndGet()
        hasPending = false
        latestPending = null
        gen
    }

    fun fenceGeneration(): Long = synchronized(lock) {
        generation.incrementAndGet()
    }

    fun currentGeneration(): Long = generation.get()

    fun submit(snapshot: T, executeTask: (snapshot: T, generation: Long) -> Unit) {
        synchronized(lock) {
            if (closed) return
            latestPending = snapshot
            hasPending = true
            if (inFlight) {
                return
            }
            inFlight = true
        }

        executor.submit {
            drainLoop(executeTask)
        }
    }

    private fun drainLoop(executeTask: (snapshot: T, generation: Long) -> Unit) {
        var released = false
        try {
            while (true) {
                val (snapshot, targetGen) = synchronized(lock) {
                    if (closed || !hasPending) {
                        inFlight = false
                        released = true
                        return
                    }
                    val snap = latestPending
                    hasPending = false
                    latestPending = null
                    val gen = generation.get()
                    @Suppress("UNCHECKED_CAST")
                    (snap as T) to gen
                }

                val future = executor.submit(Callable {
                    executeTask(snapshot, targetGen)
                })
                synchronized(lock) {
                    activeFuture = future
                }

                try {
                    future.get(boundMillis, TimeUnit.MILLISECONDS)
                } catch (_: Throwable) {
                    fenceGeneration()
                    future.cancel(true)
                } finally {
                    synchronized(lock) {
                        if (activeFuture === future) {
                            activeFuture = null
                        }
                    }
                }
            }
        } finally {
            if (!released) {
                synchronized(lock) {
                    inFlight = false
                }
            }
        }
    }

    override fun close() {
        val futureToCancel = synchronized(lock) {
            closed = true
            generation.incrementAndGet()
            hasPending = false
            latestPending = null
            inFlight = false
            val f = activeFuture
            activeFuture = null
            f
        }
        futureToCancel?.cancel(true)
    }
}
