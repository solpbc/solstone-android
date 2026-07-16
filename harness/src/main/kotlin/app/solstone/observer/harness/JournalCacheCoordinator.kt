// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.platform.persistence.room.JOURNAL_CACHE_LIMIT_CHOICES_BYTES
import app.solstone.platform.persistence.room.JournalCacheEvictionResult
import app.solstone.platform.persistence.room.JournalCacheLimitSaveResult
import app.solstone.platform.persistence.room.JournalCacheSnapshot
import java.util.concurrent.atomic.AtomicBoolean

const val JOURNAL_CACHE_ROUTINE_INTERVAL_MS = 15L * 60L * 1000L

class JournalCacheCoordinator(
    private val canRun: () -> Boolean,
    private val submit: (() -> Unit) -> Boolean,
    private val monotonicElapsedMs: () -> Long,
    private val snapshot: () -> JournalCacheSnapshot,
    private val saveLimitToStore: (Long) -> JournalCacheLimitSaveResult,
    private val nowEpochMs: () -> Long,
    private val runPass: (Long) -> JournalCacheEvictionResult,
) {
    private val closed = AtomicBoolean(false)
    private val passQueued = AtomicBoolean(false)
    private val immediateRequested = AtomicBoolean(false)

    @Volatile
    private var latestResult: JournalCacheEvictionResult? = null

    @Volatile
    private var latestSaveError: HarnessJournalCacheSaveError? = null

    @Volatile
    private var lastPassStartedAtMonotonicMs: Long? = null

    fun state(): HarnessJournalCacheState = journalCacheState(
        snapshot = snapshot(),
        result = latestResult,
        limitChoicesBytes = JOURNAL_CACHE_LIMIT_CHOICES_BYTES,
        saveError = latestSaveError,
    )

    fun saveLimit(bytes: Long): HarnessJournalCacheState {
        when (saveLimitToStore(bytes)) {
            is JournalCacheLimitSaveResult.Saved -> {
                latestSaveError = null
                requestImmediatePass()
            }
            is JournalCacheLimitSaveResult.Rejected -> latestSaveError = HarnessJournalCacheSaveError.REJECTED
            is JournalCacheLimitSaveResult.Failed -> latestSaveError = HarnessJournalCacheSaveError.FAILED
        }
        return state()
    }

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
                    val decidedAt = nowEpochMs()
                    latestResult = runPass(decidedAt)
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
