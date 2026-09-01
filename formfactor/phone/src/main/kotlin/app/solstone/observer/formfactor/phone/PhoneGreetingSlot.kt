// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * Home's heading (§ 5.4 — the deck's heading *is* the greeting).
 *
 * In the brand face at display size: this is the first thing an owner reads every day,
 * and it had been rendering at default body size in the platform face, indistinguishable
 * from the tile names under it. It also carries the `heading` role, which every other
 * pane's title already had and home did not.
 */
@Composable
fun PhoneGreetingSlot(hour: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().testTag("greetingSlot")) {
        Text(
            text = greetingFor(hour),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
    }
}
