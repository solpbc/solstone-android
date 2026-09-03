// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.solstone.observer.harness.SourceStatus

/**
 * The status pane — a card anchored to the status pill, expanding **downward**.
 *
 * ⚠ **It was a `Popup` aligned `BottomCenter` against the pill's own bounds**, which in
 * Compose aligns the popup's bottom edge to the anchor's bottom edge — so a pane taller
 * than the pill grew *upward*, off the top of the screen, covering the app bar and the
 * pill that opened it. Drawn on `colorScheme.surface`, which on this theme is the same
 * cream as the ground, the result was unstyled text floating over the deck with no
 * visible container at all. § 2.3 is specific about the shape this takes on Android:
 * *"a pill-anchored popup expanding downward"*, and a surface entering from the top
 * edge reads as a notification.
 *
 * ⛔ The direction assignment is not a platform's to re-map (§ 2.3), which is why this
 * is a positioner rather than a different presentation.
 */
@Composable
fun PhoneStatusPane(
    model: PhoneStatusModel,
    waiting: List<SourceStatus>,
    onDismiss: () -> Unit,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
    onConnectJournal: () -> Unit = {},
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.roundToPx() }
    val marginPx = with(density) { ShellMetrics.screenMargin.roundToPx() }
    Popup(
        popupPositionProvider = remember(gapPx, marginPx) {
            AnchoredBelowPositionProvider(gapPx = gapPx, screenMarginPx = marginPx)
        },
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            modifier = modifier
                .widthIn(min = 240.dp, max = 340.dp)
                .testTag("statusPane")
                .semantics { paneTitle = spokenPaneTitle(PhonePane.STATUS) },
            shape = ShellMetrics.cardShape,
            color = shellSurface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            // A popup floats over content, so it needs a shadow to separate from the
            // ground rather than a hairline alone.
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(ShellMetrics.surfacePadding).fillMaxWidth()) {
                headingText(PhonePane.STATUS)?.let { heading ->
                    Text(
                        text = heading,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .semantics { heading() }
                            .testTag("statusPaneHeading"),
                    )
                    Spacer(Modifier.height(ShellMetrics.sectionSpacing))
                }
                if (model.paired) {
                    PhonePairedStatusContent(
                        model = model,
                        waiting = waiting,
                        onOpenSource = onOpenSource,
                    )
                } else {
                    PaneLead("not paired")
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onConnectJournal,
                        modifier = Modifier.testTag("statusPaneConnect"),
                    ) {
                        Text("connect a journal")
                    }
                    PhoneStatusWaitingRows(waiting, onOpenSource)
                }
            }
        }
    }
}

@Composable
internal fun PhonePairedStatusContent(
    model: PhoneStatusModel,
    waiting: List<SourceStatus>,
    onOpenSource: (String) -> Unit,
) {
    PhonePairedStatusSummary(model)
    PhoneStatusWaitingRows(waiting, onOpenSource)
}

@Composable
private fun PhonePairedStatusSummary(model: PhoneStatusModel) {
    when (statusPillKind(model)) {
        StatusPillKind.CONNECTED -> {
            PaneLead("all caught up")
            PaneSubLine("everything is in your journal")
        }
        StatusPillKind.SYNCING -> {
            PaneCount(model.pendingCount)
            // ⛔ Locked string, and it is a KNOWN-OPEN founder question
            // (it sits against CMO's subject register). The pill, this
            // pane and the aggregate label move together or not at all —
            // do not quietly reword one of them.
            PaneSubLine("syncing to your journal…")
        }
        StatusPillKind.OFFLINE -> {
            PaneCount(model.pendingCount)
            // ⛔ Never a safety claim — only where it is.
            PaneSubLine("on this device")
        }
        StatusPillKind.NOT_PAIRED -> Unit
    }
}

@Composable
private fun PhoneStatusWaitingRows(
    waiting: List<SourceStatus>,
    onOpenSource: (String) -> Unit,
) {
    if (waiting.isEmpty()) return
    Spacer(Modifier.height(ShellMetrics.sectionSpacing))
    // § 2.2: per-source breakdowns live one level down, here. Every
    // waiting row opens that source, so status is a way in.
    waiting.forEach { status ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { onOpenSource(status.sourceId) }
                .testTag("waitingRow-${status.sourceId}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(sourceGlyph(status.sourceId)),
                    contentDescription = null,
                    tint = shellSecondaryInk,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = sourceLabel(status.sourceId),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Icon(
                painter = painterResource(R.drawable.phone_chevron_right),
                contentDescription = null,
                tint = shellSecondaryInk,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun PaneLead(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun PaneCount(count: Int) {
    Text(
        text = count.toString(),
        style = MaterialTheme.typography.headlineSmall.merge(
            TextStyle(fontFeatureSettings = "tnum"),
        ),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun PaneSubLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = shellSecondaryInk,
    )
}

/**
 * Places the pane just under its anchor, trailing-aligned, clamped inside the window.
 *
 * Compose's `Popup(alignment = …)` cannot express this: it aligns the popup *within*
 * the anchor's bounds, so any alignment that keeps the pane on screen also makes it
 * grow over the anchor. § 2.3 wants the pane to come from where its control is and
 * expand downward, which is a position, not an alignment.
 */
private class AnchoredBelowPositionProvider(
    private val gapPx: Int,
    private val screenMarginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val y = anchorBounds.bottom + gapPx
        val preferredX = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.right - popupContentSize.width
        } else {
            anchorBounds.left
        }
        val maxX = windowSize.width - popupContentSize.width - screenMarginPx
        val x = preferredX.coerceIn(screenMarginPx.coerceAtMost(maxX.coerceAtLeast(0)), maxX.coerceAtLeast(0))
        return IntOffset(x, y)
    }
}
