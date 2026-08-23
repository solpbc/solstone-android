// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.solstone.core.model.SourceState

@Composable
fun PhoneTileDot(
    state: SourceState,
    modifier: Modifier = Modifier,
) {
    val mark = tileDotMark(state)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val error = MaterialTheme.colorScheme.error
    val onGreen = LocalStatusOnGreen.current
    val color = when (mark) {
        TileDotMark.DISC -> onGreen
        TileDotMark.DIAMOND -> error
        TileDotMark.RING, TileDotMark.ARC, TileDotMark.SQUARE -> onSurface
    }
    Canvas(
        modifier
            .size(12.dp)
            .testTag("tileDot")
            .clearAndSetSemantics { },
    ) {
        drawTileDotMark(mark, color)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTileDotMark(
    mark: TileDotMark,
    color: Color,
) {
    val stroke = Stroke(width = size.minDimension * 0.18f)
    val inset = size.minDimension * 0.08f
    val diameter = size.minDimension - inset * 2
    val topLeft = Offset(inset, inset)
    val box = Size(diameter, diameter)
    when (mark) {
        TileDotMark.RING -> drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = box,
            style = stroke,
        )
        TileDotMark.ARC -> drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = topLeft,
            size = box,
            style = stroke,
        )
        TileDotMark.DISC -> drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = true,
            topLeft = topLeft,
            size = box,
        )
        TileDotMark.SQUARE -> drawRect(
            color = color,
            topLeft = topLeft,
            size = box,
        )
        TileDotMark.DIAMOND -> {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = diameter / 2f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx, cy - r)
                lineTo(cx + r, cy)
                lineTo(cx, cy + r)
                lineTo(cx - r, cy)
                close()
            }
            drawPath(path, color)
        }
    }
}
