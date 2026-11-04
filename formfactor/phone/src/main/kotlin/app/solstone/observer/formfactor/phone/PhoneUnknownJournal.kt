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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.pl.ClaimedJournalMark
import app.solstone.core.pl.UnknownJournalSighting

fun unknownJournalRowText(address: String?): String =
    address?.let { "unknown journal seen at $it" } ?: "unknown journal seen through the relay"

internal fun unknownJournalBody(address: String?): String =
    if (address != null) {
        "something answered this device at $address, and it isn't the journal this device is paired with. " +
            "this could be another device now using that address, or your journal, if its identity was reset. " +
            "if the mark under \"what answered\" matches the one your journal shows now, pair this device again."
    } else {
        "something answered this device through the relay, and it isn't the journal this device is paired with. " +
            "this is less certain than a direct connection."
    }

internal fun unknownJournalCaption(mark: ClaimedJournalMark): String? = when (mark) {
    ClaimedJournalMark.NotPresented -> "no identity presented"
    ClaimedJournalMark.Pending -> null
    is ClaimedJournalMark.Derived, ClaimedJournalMark.Unavailable -> "claimed, not verified"
}

/** What the detail route needs from the screen. A null [sightings] means status isn't known yet. */
internal class UnknownJournalsBinding(
    val sightings: List<UnknownJournalSighting>? = null,
    val onOpen: (String?) -> Unit = {},
    val onGone: () -> Unit = {},
)

/**
 * One row per sighting, address first. A row pushes its own detail, as every row in this pane does:
 * the anchored status card has no inline disclosure and no room to grow one.
 */
@Composable
internal fun PhoneUnknownJournalRows(
    sightings: List<UnknownJournalSighting>,
    onOpen: (String?) -> Unit,
) {
    sightings.forEach { sighting ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { onOpen(sighting.address) }
                .padding(horizontal = ShellMetrics.surfacePadding, vertical = 4.dp)
                .testTag("unknownJournalRow-${sighting.address ?: "relay"}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = unknownJournalRowText(sighting.address), style = MaterialTheme.typography.bodyLarge)
                Text(text = "view details", style = MaterialTheme.typography.bodySmall, color = shellSecondaryInk)
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

/** The detail of one sighting. It closes itself when the sighting ends: there is no resolved state. */
@Composable
internal fun PhoneUnknownJournalRoute(
    route: PhoneRoute.UnknownJournal,
    binding: UnknownJournalsBinding,
    yourJournal: JournalMarkPresentation,
    onPairAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sightings = binding.sightings
    val sighting = sightings?.firstOrNull { it.address == route.address }
    if (sightings != null && sighting == null) {
        LaunchedEffect(route) { binding.onGone() }
    }
    if (sighting != null) {
        PhoneUnknownJournalPane(sighting, yourJournal, onPairAgain, modifier)
    }
}

@Composable
internal fun PhoneUnknownJournalPane(
    sighting: UnknownJournalSighting,
    yourJournal: JournalMarkPresentation,
    onPairAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PhonePaneScaffold(
        modifier
            .testTag("unknownJournalPane")
            .semantics { paneTitle = spokenPaneTitle(PhoneRoute.UnknownJournal(sighting.address)) },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        Text(
            text = "something answered instead of your journal",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .semantics { heading() }
                .testTag("unknownJournalHeading"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = unknownJournalBody(sighting.address),
            style = MaterialTheme.typography.bodyMedium,
            color = shellSecondaryInk,
            modifier = Modifier.testTag("unknownJournalBody"),
        )
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MarkColumn(label = "what answered", tag = "unknownJournalWhatAnswered", modifier = Modifier.weight(1f)) {
                ClaimedMarkCard(sighting.mark)
                unknownJournalCaption(sighting.mark)?.let { caption ->
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = shellSecondaryInk,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .testTag("unknownJournalCaption"),
                    )
                }
            }
            MarkColumn(label = "your journal", tag = "unknownJournalYourJournal", modifier = Modifier.weight(1f)) {
                JournalMarkCard(yourJournal, Modifier.fillMaxWidth(), JournalMarkCardSize.Compact)
            }
        }
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        TextButton(
            onClick = onPairAgain,
            modifier = Modifier.testTag("unknownJournalPairAgain"),
        ) {
            Text(PAIR_AGAIN)
        }
    }
}

@Composable
private fun MarkColumn(
    label: String,
    tag: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.testTag(tag)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = shellSecondaryInk,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

@Composable
private fun ClaimedMarkCard(mark: ClaimedJournalMark) {
    val full = Modifier.fillMaxWidth()
    when (mark) {
        ClaimedJournalMark.NotPresented -> JournalMarkNotPresentedCard(full, JournalMarkCardSize.Compact)
        is ClaimedJournalMark.Derived ->
            JournalMarkCard(JournalMarkPresentation.Identified(mark.mark), full, JournalMarkCardSize.Compact)
        ClaimedJournalMark.Unavailable ->
            JournalMarkCard(JournalMarkPresentation.Unavailable, full, JournalMarkCardSize.Compact)
        // Holds the slot's size, so the layout doesn't move when the mark lands. Nothing to read yet.
        ClaimedJournalMark.Pending -> JournalMarkCard(
            JournalMarkPresentation.Unavailable,
            full.alpha(0f).clearAndSetSemantics { },
            JournalMarkCardSize.Compact,
        )
    }
}
