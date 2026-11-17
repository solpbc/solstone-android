// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.foldersync

import android.content.Context
import android.os.CancellationSignal
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A worker can find its live runtime or restore it through the application's source host. */
open class FolderRuntime<T : Any> {
    @Volatile var current: T? = null
        private set

    @Synchronized fun register(session: T) { current = session }
    @Synchronized fun unregister(session: T) {
        if (current === session) current = null
    }
}

abstract class FolderCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    protected abstract fun runCheck(stopped: () -> Boolean): Boolean

    final override fun doWork(): Result =
        if (runCheck { isStopped }) Result.success() else Result.retry()
}

/** Serializes one source's state, scans, and delivery checks. The source owns what a check does. */
class FolderChecks(
    private val context: Context,
    private val workName: String,
    private val worker: Class<out Worker>,
    threadName: String,
    private val check: (CancellationSignal) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val backgroundWanted: () -> Boolean,
    private val foregroundDelayMs: () -> Long? = { null },
    private val workerTimeoutMs: Long? = null,
) {
    private val actor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, threadName) }
        .apply { removeOnCancelPolicy = true }
    private var foreground = false
    private var timer: ScheduledFuture<*>? = null
    @Volatile private var operation: CancellationSignal? = null

    fun submit(action: () -> Unit) {
        actor.execute {
            try { action() } catch (error: Exception) { onFailure(error) }
        }
    }

    fun onForeground(value: Boolean) = submit {
        foreground = value
        cancelForegroundTimer()
        if (value) checkNow()
    }

    fun checkNow() {
        try {
            runCheck(CancellationSignal())
        } finally {
            scheduleForegroundTimer()
        }
    }

    fun cancelCheck() {
        operation?.cancel()
    }

    fun scheduleBackground(on: Boolean = backgroundWanted()) {
        val manager = WorkManager.getInstance(context)
        if (on) {
            val work = PeriodicWorkRequest.Builder(worker, PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            manager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, work)
        } else {
            manager.cancelUniqueWork(workName)
        }
    }

    fun stopScheduling() {
        cancelForegroundTimer()
        scheduleBackground(on = false)
    }

    private fun cancelForegroundTimer() {
        timer?.cancel(false)
        timer = null
    }

    private fun scheduleForegroundTimer() {
        cancelForegroundTimer()
        if (!foreground) return
        val delay = foregroundDelayMs() ?: return
        timer = actor.schedule(
            { try { checkNow() } catch (error: Exception) { onFailure(error) } },
            delay,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun runCheck(signal: CancellationSignal) {
        operation = signal
        try {
            signal.throwIfCanceled()
            check(signal)
        } finally {
            operation = null
        }
    }

    fun runFromWorker(stopped: () -> Boolean): Boolean {
        val signal = CancellationSignal()
        val result = enqueue {
            try {
                if (backgroundWanted()) runCheck(signal)
                true
            } catch (error: Exception) {
                onFailure(error)
                false
            }
        } ?: return false
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            while (!stopped() && !Thread.currentThread().isInterrupted) {
                val remaining = workerTimeoutMs?.minus(android.os.SystemClock.elapsedRealtime() - started)
                // A source can ask for another opportunity while its serialized check finishes.
                if (remaining != null && remaining <= 0) return false
                try {
                    return result.get(minOf(500, remaining ?: 500), TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) { /* Give WorkManager a chance to cancel. */ }
            }
        } catch (_: ExecutionException) {
            return false
        } catch (_: CancellationException) {
            return false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        signal.cancel()
        result.cancel(true)
        return false
    }

    fun <T> blocking(seconds: Long, onFailure: (Throwable) -> Unit = {}, action: () -> T): T? {
        val result = enqueue(action) ?: return null
        return try {
            result.get(seconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            result.cancel(true)
            null
        } catch (_: CancellationException) {
            null
        } catch (error: ExecutionException) {
            onFailure(error.cause ?: error)
            null
        } catch (_: InterruptedException) {
            result.cancel(true)
            Thread.currentThread().interrupt()
            null
        }
    }

    fun restartFirstSetup(reset: () -> Unit): Boolean {
        val done = blocking(30) {
            reset()
            true
        } == true
        submit { checkNow() }
        return done
    }

    fun close() {
        cancelCheck()
        actor.shutdownNow().forEach { (it as? Future<*>)?.cancel(false) }
    }

    fun awaitClosed(seconds: Long): Boolean = actor.awaitTermination(seconds, TimeUnit.SECONDS)

    private fun <T> enqueue(action: () -> T): Future<T>? =
        try { actor.submit(Callable { action() }) } catch (_: RejectedExecutionException) { null }
}
