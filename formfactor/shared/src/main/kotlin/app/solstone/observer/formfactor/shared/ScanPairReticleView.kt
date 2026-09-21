// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Decorative aiming brackets drawn over the full-bleed viewfinder preview.
 *
 * Drawn in the hierarchy between the camera preview and the caption text so the operator `Back`
 * button remains topmost at its own centre (ObserverHarnessChromeInvariantTest hit-tests controls
 * by draw order). This view is not an accessibility node (`IMPORTANT_FOR_ACCESSIBILITY_NO`) — the
 * caption remains the only accessible text on the screen.
 */
public class ScanPairReticleView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb((0.9f * 255).toInt(), 255, 255, 255)
    }

    private val path = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val geometry = scanPairReticleGeometry(
            widthPx = width.toFloat(),
            heightPx = height.toFloat(),
            density = resources.displayMetrics.density,
        )
        if (geometry.sidePx <= 0f) return

        paint.strokeWidth = geometry.strokePx

        drawPolyline(canvas, geometry.topLeft)
        drawPolyline(canvas, geometry.topRight)
        drawPolyline(canvas, geometry.bottomLeft)
        drawPolyline(canvas, geometry.bottomRight)
    }

    private fun drawPolyline(canvas: Canvas, points: List<ScanPairReticlePoint>) {
        if (points.size < 2) return
        path.rewind()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }
        canvas.drawPath(path, paint)
    }
}
