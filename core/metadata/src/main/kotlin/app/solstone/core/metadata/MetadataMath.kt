// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.metadata

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

data class Tilt(val pitchDeg: Double, val rollDeg: Double)

data class Motion(val linAccMean: Double, val linAccPeak: Double)

fun tiltFrom(sample: RotationVectorSample): Tilt {
    val x = sample.x.toDouble()
    val y = sample.y.toDouble()
    val z = sample.z.toDouble()
    val w = sample.w?.toDouble() ?: sqrt(max(0.0, 1.0 - x * x - y * y - z * z))
    val roll = atan2(2.0 * (w * x + y * z), 1.0 - 2.0 * (x * x + y * y))
    val pitch = asin((2.0 * (w * y - z * x)).coerceIn(-1.0, 1.0))
    return Tilt(pitchDeg = pitch.toDegrees(), rollDeg = roll.toDegrees())
}

fun nearestTilt(photoTs: Long, samples: List<RotationVectorSample>): Tilt? {
    val nearest = samples.minWithOrNull(
        compareBy<RotationVectorSample> { kotlin.math.abs(it.timestampEpochMs - photoTs) }
            .thenBy { it.timestampEpochMs },
    ) ?: return null
    return tiltFrom(nearest)
}

fun motionFrom(samples: List<LinearAccelerationSample>): Motion? {
    if (samples.isEmpty()) return null
    val magnitudes = samples.map { sample ->
        sqrt(
            sample.x.toDouble() * sample.x.toDouble() +
                sample.y.toDouble() * sample.y.toDouble() +
                sample.z.toDouble() * sample.z.toDouble(),
        )
    }
    return Motion(
        linAccMean = magnitudes.sum() / magnitudes.size.toDouble(),
        linAccPeak = magnitudes.maxOrNull() ?: 0.0,
    )
}

private fun Double.toDegrees(): Double = this * 180.0 / PI
