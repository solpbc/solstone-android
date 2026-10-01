// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import kotlin.math.abs

/**
 * The camera frame size the pairing scanner reads, from the sizes the camera offers.
 *
 * 🔴 **This used to be the SMALLEST size offered**, which on a Galaxy A36 is 176x144 on every
 * camera. A pairing code that filled the on-screen brackets then spanned under 90 pixels — two or
 * three pixels per module — and would not read until the phone was held far closer than the
 * brackets asked, often after a retry. The brackets are aiming guides only; the decoder reads the
 * whole frame.
 *
 * ✅ The smallest size whose short side is at least [MIN_SHORT_SIDE], so a code that fills the
 * brackets has several pixels per module, capped at [MAX_LONG_SIDE] so a frame decodes quickly.
 * Among those, one with the sensor's own shape (taken from the largest size offered) is preferred:
 * a different shape is a crop, and the A36 offers an odd 1024x738 just below its 1024x768. Without
 * any size that large, the largest size under the cap; without that, the smallest offered.
 */
fun qrAnalysisSize(sizes: List<Pair<Int, Int>>): Pair<Int, Int>? {
    if (sizes.isEmpty()) return null
    fun area(size: Pair<Int, Int>) = size.first.toLong() * size.second.toLong()
    fun shortSide(size: Pair<Int, Int>) = minOf(size.first, size.second)
    fun longSide(size: Pair<Int, Int>) = maxOf(size.first, size.second)
    fun aspect(size: Pair<Int, Int>) = longSide(size).toDouble() / shortSide(size)
    val sensorAspect = aspect(sizes.maxBy(::area))
    val capped = sizes.filter { longSide(it) <= MAX_LONG_SIDE }
    val readable = capped.filter { shortSide(it) >= MIN_SHORT_SIDE }
    return readable.filter { abs(aspect(it) - sensorAspect) <= ASPECT_TOLERANCE }.minByOrNull(::area)
        ?: readable.minByOrNull(::area)
        ?: capped.maxByOrNull(::area)
        ?: sizes.minByOrNull(::area)
}

private const val MIN_SHORT_SIDE = 720
private const val MAX_LONG_SIDE = 1920
private const val ASPECT_TOLERANCE = 0.01
