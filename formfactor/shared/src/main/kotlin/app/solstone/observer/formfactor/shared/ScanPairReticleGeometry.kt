// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import kotlin.math.min

data class ScanPairReticlePoint(val x: Float, val y: Float)

data class ScanPairReticleGeometry(
    val sidePx: Float,
    val armPx: Float,
    val strokePx: Float,
    val topLeft: List<ScanPairReticlePoint>,
    val topRight: List<ScanPairReticlePoint>,
    val bottomLeft: List<ScanPairReticlePoint>,
    val bottomRight: List<ScanPairReticlePoint>,
)

/**
 * Computes the pixel geometry for the viewfinder aiming reticle.
 *
 * All inputs and outputs are in pixels. These corner brackets are purely decorative aiming guides
 * over the full-bleed preview and do not crop or affect the decode region.
 */
fun scanPairReticleGeometry(widthPx: Float, heightPx: Float, density: Float): ScanPairReticleGeometry {
    if (widthPx <= 0f || heightPx <= 0f || density <= 0f) {
        return ScanPairReticleGeometry(
            sidePx = 0f,
            armPx = 0f,
            strokePx = 0f,
            topLeft = emptyList(),
            topRight = emptyList(),
            bottomLeft = emptyList(),
            bottomRight = emptyList(),
        )
    }
    val side = min(260f * density, min(widthPx, heightPx) * 0.64f)
    val arm = 0.24f * side
    val stroke = 4f * density
    val left = (widthPx - side) / 2f
    val top = (heightPx - side) / 2f
    val right = left + side
    val bottom = top + side

    val topLeft = listOf(
        ScanPairReticlePoint(left, top + arm),
        ScanPairReticlePoint(left, top),
        ScanPairReticlePoint(left + arm, top),
    )
    val topRight = listOf(
        ScanPairReticlePoint(right - arm, top),
        ScanPairReticlePoint(right, top),
        ScanPairReticlePoint(right, top + arm),
    )
    val bottomLeft = listOf(
        ScanPairReticlePoint(left, bottom - arm),
        ScanPairReticlePoint(left, bottom),
        ScanPairReticlePoint(left + arm, bottom),
    )
    val bottomRight = listOf(
        ScanPairReticlePoint(right - arm, bottom),
        ScanPairReticlePoint(right, bottom),
        ScanPairReticlePoint(right, bottom - arm),
    )

    return ScanPairReticleGeometry(
        sidePx = side,
        armPx = arm,
        strokePx = stroke,
        topLeft = topLeft,
        topRight = topRight,
        bottomLeft = bottomLeft,
        bottomRight = bottomRight,
    )
}
