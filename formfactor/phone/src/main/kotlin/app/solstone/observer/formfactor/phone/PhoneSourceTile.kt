// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish

/**
 * The deck tile — a real surface, not four lines of text on the ground.
 *
 * ⚠ **The previous tile had no container at all.** It was a bare `Column` with 12dp of
 * padding: no background, no shape, no border, no height. That is why the deck read as
 * loose text rather than as a grid, why rows had wildly different heights, and why
 * `import` and a source were visually indistinguishable. Every fix below is at that
 * cause rather than at the symptom.
 *
 * Composition matches the approved mock and the iOS tile it is a sibling of: the
 * source's glyph and its control share the top line, then the name in the brand face,
 * then the state (dot + word), then the state's own sub-line.
 *
 * 🔴 **Every tile fills its row** ([Modifier.fillMaxHeight] inside the grid's row), so
 * a row is one band rather than differently-sized cards top-aligned against each
 * other. That, plus a fixed column count, is what makes the deck a grid.
 */
@Composable
fun PhoneSourceTile(
    status: SourceStatus,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
    onToggle: (SourceWish) -> Unit,
    modifier: Modifier = Modifier,
    paired: Boolean = false,
) {
    val label = sourceLabel(status.sourceId)
    val stateCopy = sourceStateCopy(status.state)
    val earnsSwitch = sourceEarnsSwitch(status.sourceId)
    val wishOn = status.wish == SourceWish.On
    val subLine = sourceSubLine(status, paired)
    PhoneTileSurface(
        modifier = modifier
            .testTag("sourceTile-${status.sourceId}")
            .clearAndSetSemantics {
                // Section 5.5: the accessible VALUE is the state word from section 5.1 --
                // "the same word the sighted owner reads, nothing added and nothing
                // translated" -- and the NAME is the source's own label, so a screen
                // reader says "audio, on, button" where the screen reads audio / on.
                // Parity is the whole requirement.
                //
                // This was one merged `stateDescription = "$label $stateCopy"` with no
                // name and no role. Three consequences, all real: a screen reader
                // announced "audio on" as an undifferentiated value with nothing
                // identifying it as a control; the label was duplicated INTO the value,
                // which is the "nothing added" half of 5.5; and because the tile
                // published no name and no text, no text selector could find it --
                // the release gate had to reach a source through `add more` instead of
                // off the deck.
                contentDescription = label
                stateDescription = stateCopy
                role = Role.Button
                collectionItemInfo = CollectionItemInfo(index, count, 0, 1)
                customActions = buildList {
                    add(CustomAccessibilityAction(label) { onOpen(); true })
                    if (earnsSwitch) {
                        add(
                            CustomAccessibilityAction(if (wishOn) "off" else "on") {
                                onToggle(if (wishOn) SourceWish.Off else SourceWish.On)
                                true
                            },
                        )
                    }
                }
            },
        onClick = onOpen,
        bodyTestTag = "sourceBody-${status.sourceId}",
    ) {
        // Glyph and control share the top line. The switch keeps its own 48dp target
        // at the tile's trailing edge; the previous layout dropped it onto a row of
        // its own *below* the body, which is most of why a source tile stood half
        // again as tall as the `import` tile beside it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (earnsSwitch) 48.dp else 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                painter = painterResource(sourceGlyph(status.sourceId)),
                contentDescription = null,
                // The glyph carries the state's warmth: brand ink while the source is
                // running, the error ink when it needs attention, quiet otherwise. The
                // approved mock does the same, and it means the tile reads on/off from
                // across the room before any word is read.
                tint = when (status.state) {
                    SourceState.ON -> MaterialTheme.colorScheme.onSurfaceVariant
                    SourceState.NEEDS_ATTENTION -> MaterialTheme.colorScheme.error
                    else -> shellSecondaryInk
                },
                modifier = Modifier.size(22.dp),
            )
            if (earnsSwitch) {
                Box(
                    Modifier
                        .minimumInteractiveComponentSize()
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics(mergeDescendants = true) {}
                        .testTag("sourceSwitch-${status.sourceId}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Switch(
                        checked = wishOn,
                        onCheckedChange = { checked ->
                            onToggle(if (checked) SourceWish.On else SourceWish.Off)
                        },
                    )
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            modifier = Modifier
                .padding(top = 6.dp)
                .testTag("sourceLabel-${status.sourceId}"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhoneTileDot(status.state)
            Text(
                text = stateCopy,
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.state == SourceState.NEEDS_ATTENTION) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (subLine != null) {
            Text(
                text = subLine,
                style = MaterialTheme.typography.bodySmall,
                color = shellSecondaryInk,
                maxLines = 3,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The one tile container.
 *
 * `import` and `add more` use it too, so a destination tile and a source tile are the
 * same object with different contents — which is what § 2.1's parity rule (an even
 * grid that must not promote one thing over another) actually requires. ⛔ Do not add
 * a second tile surface for a new kind of tile; that is how two drawings of one object
 * drift, which is exactly what happened to the journal mark on iOS.
 */
@Composable
internal fun PhoneTileSurface(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    bodyTestTag: String? = null,
    dashed: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hairline = shellHairline
    val surface = shellSurface
    Column(
        modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .heightIn(
                min = if (dashed) ShellMetrics.utilTileMinHeight else ShellMetrics.tileMinHeight,
            )
            .then(
                if (dashed) {
                    Modifier.shellDashedSurface(hairline, ShellMetrics.tileRadius)
                } else {
                    Modifier.shellSurface(surface, hairline, ShellMetrics.tileShape)
                },
            )
            .clickable(onClick = onClick)
            .padding(ShellMetrics.tilePadding)
            .then(if (bodyTestTag != null) Modifier.testTag(bodyTestTag) else Modifier),
        // ⚠ A rhythm, not a height. `PhoneDeckSourceCheckTest` forbids any fixed
        // height in a tile so the tile always grows with the owner's text size, and an
        // arrangement satisfies that by construction rather than by review.
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}
