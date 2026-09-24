// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import java.util.concurrent.atomic.AtomicBoolean

const val JOURNAL_CACHE_ROUTINE_INTERVAL_MS = 15L * 60L * 1000L

class JournalCacheCoordinator(
    private val canRun: () -> Boolean,
    private val submit: (() -> Unit) -> Boolean,
    private val monotonicElapsedMs: () -> Long,
    private val runPass: () -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val passQueued = AtomicBoolean(false)
    private val immediateRequested = AtomicBoolean(false)

    @Volatile
    private var lastPassStartedAtMonotonicMs: Long? = null

    fun requestRoutinePass() {
        if (closed.get() || !canRun()) return
        val lastStarted = lastPassStartedAtMonotonicMs
        if (lastStarted != null && monotonicElapsedMs() - lastStarted < JOURNAL_CACHE_ROUTINE_INTERVAL_MS) return
        enqueuePass()
    }

    fun requestImmediatePass() {
        if (closed.get() || !canRun()) return
        immediateRequested.set(true)
        enqueuePass()
    }

    fun close() {
        closed.set(true)
    }

    private fun enqueuePass() {
        if (closed.get() || !passQueued.compareAndSet(false, true)) return
        val accepted = submit {
            try {
                while (!closed.get()) {
                    immediateRequested.set(false)
                    lastPassStartedAtMonotonicMs = monotonicElapsedMs()
                    runPass()
                    if (!immediateRequested.get()) break
                }
            } finally {
                passQueued.set(false)
                if (immediateRequested.get() && !closed.get()) enqueuePass()
            }
        }
        if (!accepted) passQueued.set(false)
    }
}
