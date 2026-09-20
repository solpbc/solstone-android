// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.solstone.core.identity.JournalMarkPresentation

/**
 * The journal pill's contents: the mark's chips beside its words, on a floating
 * cream-bright surface — the approved mock's `.jpill`.
 *
 * ⚠ This is the one place the mark is drawn **inline** (chips beside words) rather than
 * in the card's chips-above-words layout, and it is not a violation of § 2.2: § 7 item
 * 5 scopes the pill explicitly ("the home pill carries the chip pair *and* the words"),
 * and the approved mock renders it exactly this way at chip side 22.
 *
 * Unpaired, the words are `connect a journal` — § 3's locked no-journal action, which
 * is what the approved mock puts here. ⛔ Not `open in journal` or any other action
 * label once a journal *is* paired: that slot is the journal's identity, and rendering
 * an action there is the defect the iOS pass fixed.
 */
@Composable
fun PhoneJournalMarkPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    paired: Boolean = false,
    presentation: JournalMarkPresentation = JournalMarkPresentation.Generic,
) {
    val mark = (presentation as? JournalMarkPresentation.Identified)?.mark
    val isUnavailable = presentation is JournalMarkPresentation.Unavailable
    // 🔴 The two words are joined by a middot, here as everywhere else: § 2.2 makes the join
    // part of how the mark is named, and § 4.3 / § 4.4 put `your`/`journal` and
    // `mark`/`unavailable` in the same two-word slot. This pill had been joining them with a
    // plain space, so the home screen named the journal differently from the pairing screen,
    // the sheet's own header and the page an owner compares it against.
    val wordPair: Pair<String, String>? = when {
        !paired -> null
        mark != null && mark.words.size >= 2 -> mark.words[0] to mark.words[1]
        isUnavailable -> "mark" to "unavailable"
        else -> "your" to "journal"
    }
    val accessibleName = when {
        !paired -> "connect a journal"
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
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .shellSurface(shellSurface, shellHairline, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = accessibleName
            }
            .testTag("journalMarkPill"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JournalMarkChips(side = 22.dp, mark = mark, unavailable = isUnavailable)
        Spacer(Modifier.width(9.dp))
        val wordStyle = TextStyle(
            fontFamily = ComfortaaBold,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
        if (wordPair == null) {
            Text(
                text = "connect a journal",
                style = wordStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            // Two roles, never one whole-line colour (§ 2.2): the words are the claim, the
            // middot is its quiet join. The pill sits on the shell surface rather than the
            // mark card, so both inks come from the theme.
            Text(text = wordPair.first, style = wordStyle, color = MaterialTheme.colorScheme.onSurface)
            Text(text = " · ", style = wordStyle, color = shellSecondaryInk)
            Text(text = wordPair.second, style = wordStyle, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
