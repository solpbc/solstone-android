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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
) {
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .shellSurface(shellSurface, shellHairline, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                // § 4.3: never the words alone — "your journal" with no qualifier
                // asserts an identity that does not exist yet.
                contentDescription = JournalMarkTokens.GENERIC_ACCESSIBLE_NAME
            }
            .testTag("journalMarkPill"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JournalMarkChips(side = 22.dp)
        Spacer(Modifier.width(9.dp))
        Text(
            text = "connect a journal",
            fontFamily = ComfortaaBold,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
