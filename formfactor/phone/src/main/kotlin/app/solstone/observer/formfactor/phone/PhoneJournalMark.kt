// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.solstone.core.identity.JournalMark
import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.pl.JournalIdentityRefreshCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Journal mark design tokens.
 */
object JournalMarkTokens {
    val chipOneBorder = SolstoneColors.markGenericChipOne      // dashed sol orange
    val chipTwoBorder = SolstoneColors.markGenericChipTwo      // dashed mark-palette gold
    const val GENERIC_TINT_ALPHA = 0.07f
    const val IDENTIFIED_TINT_ALPHA = 0.12f                    // § 2.1; identified only
    const val RADIUS_RATIO = 0.25f                             // 0.25 × side
    const val BORDER_RATIO = 2f / 48f
    const val GAP_RATIO = 0.23f
    const val DASH_ON_RATIO = 3.2f / 27f
    const val DASH_OFF_RATIO = 2.4f / 27f
    val wordInk = SolstoneColors.surfaceDark                   // #1A1A1A
    val middotInk = SolstoneColors.markMiddotInk               // #6E6453
    val cardFill = SolstoneColors.markCardFill                 // warm-bright #FFFDF9
    val cardBorder = SolstoneColors.markCardBorder             // warm hairline
    val unavailableBorder = SolstoneColors.markUnavailableBorder
    val unavailableFill = SolstoneColors.markUnavailableFill
    val unavailableGlyph = SolstoneColors.markUnavailableGlyph
    val cardRadius = 12.dp
    const val GENERIC_ACCESSIBLE_NAME = "your journal, not set up yet"
    const val LOADING_ACCESSIBLE_NAME = "your journal, mark loading"
    const val UNAVAILABLE_ACCESSIBLE_NAME = "your journal's mark, unavailable right now"
}

fun parseHexColor(hex: String, defaultColor: Color): Color {
    val trimmed = hex.trim().removePrefix("#")
    if (trimmed.length != 6) return defaultColor
    val rgb = trimmed.toIntOrNull(radix = 16) ?: return defaultColor
    val packed = (0xFF shl 24) or rgb
    return Color(packed)
}

/**
 * Parses simple inner SVG elements and draws them into the canvas draw scope.
 */
private fun DrawScope.drawSvgMarkup(svg: String, color: Color, chipSide: Float) {
    val glyphStroke = Stroke(
        width = chipSide * 0.07f,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
    val scale = (chipSide * 0.55f) / 24f
    val offsetX = chipSide * 0.225f
    val offsetY = chipSide * 0.225f

    val elementRegex = Regex("<(path|circle|line|rect|ellipse|polyline|polygon)\\s+([^>]*)/?>")
    val attrRegex = Regex("""([a-zA-Z0-9_\-]+)\s*=\s*["']([^"']*)["']""")

    elementRegex.findAll(svg).forEach { match ->
        val tag = match.groupValues[1]
        val attrsStr = match.groupValues[2]
        val attrs = attrRegex.findAll(attrsStr).associate { it.groupValues[1] to it.groupValues[2] }

        when (tag) {
            "path" -> {
                val d = attrs["d"] ?: return@forEach
                val androidPath = androidx.core.graphics.PathParser.createPathFromPathData(d)
                val composePath = androidPath.asComposePath()
                val matrix = androidx.compose.ui.graphics.Matrix().apply {
                    translate(offsetX, offsetY)
                    scale(scale, scale)
                }
                composePath.transform(matrix)
                drawPath(composePath, color = color, style = glyphStroke)
            }
            "circle" -> {
                val cx = (attrs["cx"]?.toFloatOrNull() ?: 0f) * scale + offsetX
                val cy = (attrs["cy"]?.toFloatOrNull() ?: 0f) * scale + offsetY
                val r = (attrs["r"]?.toFloatOrNull() ?: 0f) * scale
                drawCircle(color = color, radius = r, center = Offset(cx, cy), style = glyphStroke)
            }
            "ellipse" -> {
                val cx = (attrs["cx"]?.toFloatOrNull() ?: 0f) * scale + offsetX
                val cy = (attrs["cy"]?.toFloatOrNull() ?: 0f) * scale + offsetY
                val rx = (attrs["rx"]?.toFloatOrNull() ?: 0f) * scale
                val ry = (attrs["ry"]?.toFloatOrNull() ?: 0f) * scale
                val topLeft = Offset(cx - rx, cy - ry)
                val size = Size(rx * 2f, ry * 2f)
                drawOval(color = color, topLeft = topLeft, size = size, style = glyphStroke)
            }
            "line" -> {
                val x1 = (attrs["x1"]?.toFloatOrNull() ?: 0f) * scale + offsetX
                val y1 = (attrs["y1"]?.toFloatOrNull() ?: 0f) * scale + offsetY
                val x2 = (attrs["x2"]?.toFloatOrNull() ?: 0f) * scale + offsetX
                val y2 = (attrs["y2"]?.toFloatOrNull() ?: 0f) * scale + offsetY
                drawLine(color = color, start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = glyphStroke.width, cap = StrokeCap.Round)
            }
            "rect" -> {
                val x = (attrs["x"]?.toFloatOrNull() ?: 0f) * scale + offsetX
                val y = (attrs["y"]?.toFloatOrNull() ?: 0f) * scale + offsetY
                val w = (attrs["width"]?.toFloatOrNull() ?: 0f) * scale
                val h = (attrs["height"]?.toFloatOrNull() ?: 0f) * scale
                val rx = (attrs["rx"]?.toFloatOrNull() ?: 0f) * scale
                if (rx > 0f) {
                    drawRoundRect(color = color, topLeft = Offset(x, y), size = Size(w, h), cornerRadius = CornerRadius(rx, rx), style = glyphStroke)
                } else {
                    drawRect(color = color, topLeft = Offset(x, y), size = Size(w, h), style = glyphStroke)
                }
            }
            "polyline", "polygon" -> {
                val pointsStr = attrs["points"] ?: return@forEach
                val coords = pointsStr.trim().split(Regex("""[\s,]+""")).mapNotNull { it.toFloatOrNull() }
                if (coords.size >= 4) {
                    val p = androidx.compose.ui.graphics.Path()
                    p.moveTo(coords[0] * scale + offsetX, coords[1] * scale + offsetY)
                    var i = 2
                    while (i + 1 < coords.size) {
                        p.lineTo(coords[i] * scale + offsetX, coords[i + 1] * scale + offsetY)
                        i += 2
                    }
                    if (tag == "polygon") p.close()
                    drawPath(p, color = color, style = glyphStroke)
                }
            }
        }
    }
}

/**
 * One chip: a tinted, bordered tile holding a glyph.
 */
@Composable
private fun JournalMarkChip(
    side: Dp,
    border: Color,
    rotationDegrees: Float,
    isGeneric: Boolean = false,
    isUnavailable: Boolean = false,
    glyphMarkup: String? = null,
) {
    Box(
        Modifier
            .size(side)
            .rotate(rotationDegrees),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(side)) {
            val s = size.minDimension
            val strokeWidth = s * JournalMarkTokens.BORDER_RATIO
            val radius = CornerRadius(s * JournalMarkTokens.RADIUS_RATIO)
            val inset = strokeWidth / 2f
            val boxSize = Size(s - strokeWidth, s - strokeWidth)
            val topLeft = Offset(inset, inset)
            drawRoundRect(
                color = if (isUnavailable) {
                    JournalMarkTokens.unavailableFill
                } else {
                    border.copy(
                        alpha = if (isGeneric) {
                            JournalMarkTokens.GENERIC_TINT_ALPHA
                        } else {
                            JournalMarkTokens.IDENTIFIED_TINT_ALPHA
                        },
                    )
                },
                topLeft = topLeft,
                size = boxSize,
                cornerRadius = radius,
            )
            if (isGeneric) {
                drawRoundRect(
                    color = border,
                    topLeft = topLeft,
                    size = boxSize,
                    cornerRadius = radius,
                    style = Stroke(
                        width = strokeWidth,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(
                                s * JournalMarkTokens.DASH_ON_RATIO,
                                s * JournalMarkTokens.DASH_OFF_RATIO,
                            ),
                        ),
                    ),
                )
            } else {
                drawRoundRect(
                    color = border,
                    topLeft = topLeft,
                    size = boxSize,
                    cornerRadius = radius,
                    style = Stroke(width = strokeWidth),
                )
                if (glyphMarkup != null) {
                    drawSvgMarkup(glyphMarkup, border, s)
                }
            }
        }
        if (isUnavailable) {
            Text(
                text = "?",
                color = JournalMarkTokens.unavailableGlyph,
                fontSize = (side.value * 0.58f).sp,
            )
        }
    }
}

/**
 * Chip pair: chip 1 and chip 2.
 *
 * A rotated chip's true bounding box is wider than its side (`S · 1.4143` diagonal),
 * so any rotated chip is wrapped in a reserved square to avoid corner clipping.
 */
@Composable
fun JournalMarkChips(
    side: Dp,
    mark: JournalMark? = null,
    unavailable: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val diamondBox = side * 1.4143f
    val icon1 = mark?.icon1
    val icon2 = mark?.icon2

    val color1 = if (unavailable) JournalMarkTokens.unavailableBorder else if (icon1 != null) parseHexColor(icon1.colorHex, JournalMarkTokens.chipOneBorder) else JournalMarkTokens.chipOneBorder
    val color2 = if (unavailable) JournalMarkTokens.unavailableBorder else if (icon2 != null) parseHexColor(icon2.colorHex, JournalMarkTokens.chipTwoBorder) else JournalMarkTokens.chipTwoBorder

    val rot1 = if (unavailable) 0f else (icon1?.rot ?: 0).toFloat()
    val rot2 = if (unavailable) 0f else (icon2?.rot ?: 45).toFloat()

    val isGeneric = mark == null && !unavailable

    Row(
        modifier = modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rot1 != 0f) {
            Box(Modifier.size(diamondBox), contentAlignment = Alignment.Center) {
                JournalMarkChip(
                    side = side,
                    border = color1,
                    rotationDegrees = rot1,
                    isGeneric = isGeneric,
                    isUnavailable = unavailable,
                    glyphMarkup = icon1?.svg,
                )
            }
        } else {
            JournalMarkChip(
                side = side,
                border = color1,
                rotationDegrees = rot1,
                isGeneric = isGeneric,
                isUnavailable = unavailable,
                glyphMarkup = icon1?.svg,
            )
        }

        Spacer(Modifier.width(side * JournalMarkTokens.GAP_RATIO))

        if (rot2 != 0f) {
            Box(Modifier.size(diamondBox), contentAlignment = Alignment.Center) {
                JournalMarkChip(
                    side = side,
                    border = color2,
                    rotationDegrees = rot2,
                    isGeneric = isGeneric,
                    isUnavailable = unavailable,
                    glyphMarkup = icon2?.svg,
                )
            }
        } else {
            JournalMarkChip(
                side = side,
                border = color2,
                rotationDegrees = rot2,
                isGeneric = isGeneric,
                isUnavailable = unavailable,
                glyphMarkup = icon2?.svg,
            )
        }
    }
}

/**
 * Journal mark word pair: word 1 and word 2.
 */
@Composable
fun JournalMarkWords(
    first: String,
    second: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val style = TextStyle(
        fontFamily = ComfortaaBold,
        fontWeight = FontWeight.Bold,
        fontSize = fontSize,
    )
    Row(
        modifier = modifier.clearAndSetSemantics { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = first, color = JournalMarkTokens.wordInk, style = style)
        Text(text = " · ", color = JournalMarkTokens.middotInk, style = style)
        Text(text = second, color = JournalMarkTokens.wordInk, style = style)
    }
}

/**
 * Journal mark card with chips and words.
 */
@Composable
fun JournalMarkCard(
    presentation: JournalMarkPresentation = JournalMarkPresentation.Generic,
    modifier: Modifier = Modifier,
) {
    val mark = (presentation as? JournalMarkPresentation.Identified)?.mark
    val isUnavailable = presentation is JournalMarkPresentation.Unavailable

    val word1 = mark?.words?.getOrNull(0) ?: if (isUnavailable) "mark" else "your"
    val word2 = mark?.words?.getOrNull(1) ?: if (isUnavailable) "unavailable" else "journal"

    val accessibleName = when {
        mark != null -> listOf(
            mark.icon1.colorName,
            mark.icon2.colorName,
            mark.words.getOrNull(0),
            mark.words.getOrNull(1),
        ).filterNotNull().joinToString(", ")
        isUnavailable -> JournalMarkTokens.UNAVAILABLE_ACCESSIBLE_NAME
        presentation is JournalMarkPresentation.Loading -> JournalMarkTokens.LOADING_ACCESSIBLE_NAME
        else -> JournalMarkTokens.GENERIC_ACCESSIBLE_NAME
    }

    Column(
        modifier = modifier
            .shellSurface(
                color = JournalMarkTokens.cardFill,
                hairline = JournalMarkTokens.cardBorder,
                shape = RoundedCornerShape(JournalMarkTokens.cardRadius),
            )
            .padding(horizontal = 22.dp, vertical = 18.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = accessibleName
            }
            .testTag("journalMarkCard"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        JournalMarkChips(side = 48.dp, mark = mark, unavailable = isUnavailable)
        Spacer(Modifier.height(12.dp))
        JournalMarkWords(first = word1, second = word2, fontSize = 18.sp)
    }
}

@Composable
fun PairingSuccessMark(
    coordinator: JournalIdentityRefreshCoordinator?,
    onConfirmed: () -> Unit = {},
    onMismatch: () -> PairingMismatchResult = { PairingMismatchResult.Disconnected },
    modifier: Modifier = Modifier,
) {
    var presentation by remember {
        mutableStateOf(coordinator?.currentPresentation() ?: JournalMarkPresentation.Generic)
    }
    val view = LocalView.current
    DisposableEffect(coordinator) {
        if (coordinator != null) {
            val remove = coordinator.addListener { newPres ->
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    presentation = newPres
                } else {
                    view.post { presentation = newPres }
                }
            }
            onDispose { remove() }
        } else {
            onDispose { }
        }
    }
    var confirmation by remember { mutableStateOf(PairingConfirmation.Waiting) }
    var mismatchResult by remember { mutableStateOf<PairingMismatchResult?>(null) }
    val scope = rememberCoroutineScope()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        JournalMarkCard(presentation = presentation)
        Spacer(Modifier.height(20.dp))
        when (confirmation) {
            PairingConfirmation.Waiting -> {
                Text("does this match your journal?")
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    confirmation = PairingConfirmation.Confirmed
                    onConfirmed()
                }) { Text("yes, this is my journal") }
                Button(onClick = {
                    confirmation = PairingConfirmation.Removing
                    scope.launch {
                        mismatchResult = withContext(Dispatchers.IO) { onMismatch() }
                        confirmation = if (mismatchResult == PairingMismatchResult.LocalFailure) {
                            PairingConfirmation.Failed
                        } else {
                            onConfirmed()
                            PairingConfirmation.Mismatched
                        }
                    }
                }) { Text("that doesn't match") }
            }
            PairingConfirmation.Removing -> Text("disconnecting this phone…")
            PairingConfirmation.Confirmed -> Text("this phone is connected to your journal.")
            PairingConfirmation.Mismatched -> Text(
                if (mismatchResult == PairingMismatchResult.JournalUnreached) {
                    "this phone is no longer connected. your journal may still have this phone listed."
                } else {
                    "this phone is no longer connected to that journal."
                },
            )
            PairingConfirmation.Failed -> {
                Text("couldn't disconnect this phone. try again.")
                Button(onClick = { confirmation = PairingConfirmation.Waiting }) { Text("try again") }
            }
        }
    }
}

enum class PairingMismatchResult { Disconnected, JournalUnreached, LocalFailure }

private enum class PairingConfirmation { Waiting, Confirmed, Removing, Mismatched, Failed }

fun createPhonePairingMarkView(
    context: Context,
    coordinator: JournalIdentityRefreshCoordinator?,
    onConfirmed: () -> Unit,
    onMismatch: () -> PairingMismatchResult,
): View {
    return ComposeView(context).apply {
        setContent {
            PairingSuccessMark(
                coordinator = coordinator,
                onConfirmed = onConfirmed,
                onMismatch = onMismatch,
            )
        }
    }
}
