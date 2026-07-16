// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import kotlin.math.min

data class QrPreviewTransform(
    val scaleX: Float,
    val scaleY: Float,
    val pivotX: Float,
    val pivotY: Float,
)

fun qrPreviewTransform(
    viewWidth: Int,
    viewHeight: Int,
    bufferWidth: Int,
    bufferHeight: Int,
    rotationDegrees: Int,
): QrPreviewTransform {
    if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) {
        return QrPreviewTransform(1f, 1f, 0f, 0f)
    }
    val swapBufferAxes = Math.floorMod(rotationDegrees, 180) == 90
    val rotatedBufferWidth = if (swapBufferAxes) bufferHeight else bufferWidth
    val rotatedBufferHeight = if (swapBufferAxes) bufferWidth else bufferHeight
    val implicitX = viewWidth.toFloat() / rotatedBufferWidth
    val implicitY = viewHeight.toFloat() / rotatedBufferHeight
    val effective = min(implicitX, implicitY)
    return QrPreviewTransform(
        scaleX = effective / implicitX,
        scaleY = effective / implicitY,
        pivotX = viewWidth / 2f,
        pivotY = viewHeight / 2f,
    )
}
