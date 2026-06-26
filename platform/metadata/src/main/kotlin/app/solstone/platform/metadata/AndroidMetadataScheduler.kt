// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.metadata

import app.solstone.core.metadata.Cancellable
import app.solstone.core.metadata.MetadataScheduler
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class AndroidMetadataScheduler : MetadataScheduler {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "solstone-metadata-source").also { it.isDaemon = true }
    }

    override fun nowEpochMs(): Long = System.currentTimeMillis()

    override fun execute(task: () -> Unit) {
        executor.submit(task)
    }

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val future = executor.schedule(task, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        return Cancellable { future.cancel(false) }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
