// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal enum class JournalSetupButtonVariant { Filled, Outlined }

internal val JournalSetupButtonVariantKey = SemanticsPropertyKey<JournalSetupButtonVariant>(
    name = "JournalSetupButtonVariant",
)

/** The doors sit side by side from this width, and stack below it. */
private val DoorsSideBySideMinWidth = 480.dp

/** Past this the doors' words need the full width, whatever the window. */
private const val DOORS_STACK_FONT_SCALE = 1.5f

/** The column's limit, so a door on a tablet stays the size of a tile rather than a banner. */
private val SetupMaxWidth = 560.dp

/**
 * The one way from no journal to a journal: two doors of equal weight, set one up or connect
 * the one you have, in the deck's own tile language.
 *
 * [showsDataLine] is off where a journal is already paired, because the line describes a phone
 * with none. Every no-journal surface says it, and says it only here.
 */
@Composable
fun PhoneJournalSetup(
    onSetOneUp: () -> Unit,
    onSendLink: () -> Unit,
    onAlreadyHaveOne: () -> Unit,
    modifier: Modifier = Modifier,
    showsDataLine: Boolean = true,
) {
    Column(
        modifier = modifier
            .widthIn(max = SetupMaxWidth)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ShellMetrics.gutter),
    ) {
        Text(
            text = "today, it lives on a computer you own.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sideBySide = maxWidth >= DoorsSideBySideMinWidth &&
                LocalDensity.current.fontScale < DOORS_STACK_FONT_SCALE
            if (sideBySide) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(ShellMetrics.gutter),
                ) {
                    NewJournalDoor(onSetOneUp, onSendLink, Modifier.weight(1f).fillMaxHeight())
                    HaveOneDoor(onAlreadyHaveOne, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(ShellMetrics.gutter)) {
                    NewJournalDoor(onSetOneUp, onSendLink, Modifier.fillMaxWidth())
                    HaveOneDoor(onAlreadyHaveOne, Modifier.fillMaxWidth())
                }
            }
        }
        if (showsDataLine) {
            Text(
                text = "what you share stays on this device and isn't processed until you connect a journal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun NewJournalDoor(
    onSetOneUp: () -> Unit,
    onSendLink: () -> Unit,
    modifier: Modifier,
) {
    JournalSetupDoor(
        glyph = R.drawable.phone_source_screen,
        title = "set up a journal",
        subLine = null,
        testTag = "journalSetupNewDoor",
        modifier = modifier,
    ) {
        Button(
            onClick = onSetOneUp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("journalSetupSetOneUp")
                .semantics { this[JournalSetupButtonVariantKey] = JournalSetupButtonVariant.Filled },
        ) {
            Text("open the setup page")
        }
        OutlinedButton(
            onClick = onSendLink,
            colors = secondaryDoorButtonColors(),
            border = secondaryDoorButtonBorder(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("journalSetupSendLink")
                .semantics { this[JournalSetupButtonVariantKey] = JournalSetupButtonVariant.Outlined },
        ) {
            Text("send yourself the link")
        }
    }
}

@Composable
private fun HaveOneDoor(
    onAlreadyHaveOne: () -> Unit,
    modifier: Modifier,
) {
    JournalSetupDoor(
        glyph = R.drawable.phone_scan_code,
        title = "i already have one",
        subLine = "scan its pairing code",
        testTag = "journalSetupHaveOneDoor",
        modifier = modifier,
    ) {
        OutlinedButton(
            onClick = onAlreadyHaveOne,
            colors = secondaryDoorButtonColors(),
            border = secondaryDoorButtonBorder(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("journalSetupAlreadyHave")
                .semantics { this[JournalSetupButtonVariantKey] = JournalSetupButtonVariant.Outlined },
        ) {
            Text("connect")
        }
    }
}

/** The quieter button: ink, with an edge that shows in either appearance. */
@Composable
private fun secondaryDoorButtonColors() = ButtonDefaults.outlinedButtonColors(
    contentColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun secondaryDoorButtonBorder() = BorderStroke(
    width = 1.dp,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
)

/** One door: a tile with a glyph and name, an optional sub-line, and its actions at the bottom. */
@Composable
private fun JournalSetupDoor(
    @DrawableRes glyph: Int,
    title: String,
    subLine: String?,
    testTag: String,
    modifier: Modifier,
    actions: @Composable ColumnScope.() -> Unit,
) {
    // SpaceBetween, not a weighted spacer: side by side the doors share a height and their
    // buttons line up, and a door given no height of its own stays the size of its content.
    Column(
        modifier = modifier
            .shellSurface(shellSurface, shellHairline, ShellMetrics.tileShape)
            .testTag(testTag)
            .padding(ShellMetrics.surfacePadding),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painter = painterResource(glyph),
                    contentDescription = null,
                    tint = shellSecondaryInk,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = ComfortaaBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (subLine != null) {
                Text(
                    text = subLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = shellSecondaryInk,
                )
            }
        }
        Column(
            modifier = Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = actions,
        )
    }
}
