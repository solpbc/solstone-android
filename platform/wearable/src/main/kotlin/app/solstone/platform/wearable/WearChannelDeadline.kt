// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.wearable

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounds one transfer attempt, without expiring or deleting its durable material. */
class WearChannelDeadline(
    timeoutMs: Long = 240_000,
    private val closeChannel: () -> Unit,
) : Closeable {
    companion object {
        private val timer = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "wear-channel-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }

    private val closed = AtomicBoolean(false)
    private val stream = AtomicReference<Closeable?>()
    private val future = AtomicReference<ScheduledFuture<*>?>()

    @Volatile
    var timedOut: Boolean = false
        private set

    init {
        require(timeoutMs > 0)
        future.set(timer.schedule({ finish(expired = true) }, timeoutMs, TimeUnit.MILLISECONDS))
        if (closed.get()) future.getAndSet(null)?.cancel(false)
    }

    fun <T : Closeable> own(value: T): T {
        if (!stream.compareAndSet(null, value)) {
            runCatching { value.close() }
            throw IllegalStateException("Channel stream already assigned")
        }
        if (closed.get()) {
            runCatching { stream.getAndSet(null)?.close() }
            throw IOException("Channel attempt already closed")
        }
        return value
    }

    override fun close() = finish(expired = false)

    private fun finish(expired: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        timedOut = expired
        future.getAndSet(null)?.cancel(false)
        // Dispatch SDK channel closure before releasing the local stream. The
        // callback creates its Task under WearTaskPolicy and must never await it.
        runCatching(closeChannel)
        runCatching { stream.getAndSet(null)?.close() }
    }
}
