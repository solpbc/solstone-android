// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.metadata

object PhotoMetadataContract {
    const val SOURCE_ID = "metadata"
    const val PAYLOAD_NAME = "metadata.jsonl"
    const val MEDIA_TYPE = "application/x-ndjson"
    const val WINDOW_MS = 300_000L
    const val SNAPSHOT_MS = 2_000L
}

interface BatterySource {
    fun snapshot(): BatterySnapshot?
}

data class BatterySnapshot(
    val level: Int?,
    val status: BatteryStatus?,
    val tempC: Double?,
)

enum class BatteryStatus(val wireValue: String) {
    CHARGING("charging"),
    FULL("full"),
    DISCHARGING("discharging"),
    NOT_CHARGING("not_charging"),
    UNKNOWN("unknown"),
}

interface ImuSensorPort {
    fun start(listener: ImuListener): ImuHandle
}

interface ImuHandle {
    fun stop()
}

interface ImuListener {
    fun onRotationVector(sample: RotationVectorSample)
    fun onLinearAcceleration(sample: LinearAccelerationSample)
}

data class RotationVectorSample(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float?,
    val timestampEpochMs: Long,
)

data class LinearAccelerationSample(
    val x: Float,
    val y: Float,
    val z: Float,
    val timestampEpochMs: Long,
)

interface MetadataScheduler {
    fun nowEpochMs(): Long
    fun execute(task: () -> Unit)
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}

fun interface Cancellable {
    fun cancel()
}
