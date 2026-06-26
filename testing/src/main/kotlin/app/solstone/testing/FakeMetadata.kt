// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.testing

import app.solstone.core.metadata.BatterySnapshot
import app.solstone.core.metadata.BatterySource
import app.solstone.core.metadata.Cancellable
import app.solstone.core.metadata.ImuHandle
import app.solstone.core.metadata.ImuListener
import app.solstone.core.metadata.ImuSensorPort
import app.solstone.core.metadata.LinearAccelerationSample
import app.solstone.core.metadata.MetadataScheduler
import app.solstone.core.metadata.RotationVectorSample
import java.util.PriorityQueue

class FakeBatterySource(var next: BatterySnapshot?) : BatterySource {
    override fun snapshot(): BatterySnapshot? = next
}

class FakeImuSensorPort : ImuSensorPort {
    var activeRegistrations: Int = 0
        private set
    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set
    private val activeListeners = mutableListOf<ImuListener>()
    val listeners: List<ImuListener>
        get() = activeListeners.toList()

    override fun start(listener: ImuListener): ImuHandle {
        activeRegistrations += 1
        startCount += 1
        activeListeners += listener
        return object : ImuHandle {
            private var stopped = false

            override fun stop() {
                if (stopped) return
                stopped = true
                activeRegistrations -= 1
                stopCount += 1
                activeListeners -= listener
            }
        }
    }

    fun emitRotation(sample: RotationVectorSample) {
        activeListeners.lastOrNull()?.onRotationVector(sample)
    }

    fun emitRotation(listenerIndex: Int, sample: RotationVectorSample) {
        activeListeners.getOrNull(listenerIndex)?.onRotationVector(sample)
    }

    fun emitLinear(sample: LinearAccelerationSample) {
        activeListeners.lastOrNull()?.onLinearAcceleration(sample)
    }

    fun emitLinear(listenerIndex: Int, sample: LinearAccelerationSample) {
        activeListeners.getOrNull(listenerIndex)?.onLinearAcceleration(sample)
    }
}

class FakeMetadataScheduler(startEpochMs: Long = 0L) : MetadataScheduler {
    private val queue = PriorityQueue<Scheduled>(compareBy<Scheduled> { it.dueAt }.thenBy { it.sequence })
    private var nextSequence = 0L
    private var now = startEpochMs

    override fun nowEpochMs(): Long = now

    override fun execute(task: () -> Unit) {
        task()
    }

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val scheduled = Scheduled(
            dueAt = now + delayMs.coerceAtLeast(0L),
            sequence = nextSequence++,
            task = task,
        )
        queue += scheduled
        return object : Cancellable {
            override fun cancel() {
                scheduled.cancelled = true
            }
        }
    }

    fun advanceBy(ms: Long) {
        advanceTo(now + ms)
    }

    fun advanceTo(epochMs: Long) {
        require(epochMs >= now) { "cannot move fake time backwards" }
        while (true) {
            val next = queue.peek() ?: break
            if (next.dueAt > epochMs) break
            queue.remove()
            now = next.dueAt
            if (!next.cancelled) next.task()
        }
        now = epochMs
    }

    private data class Scheduled(
        val dueAt: Long,
        val sequence: Long,
        val task: () -> Unit,
        var cancelled: Boolean = false,
    )
}
