// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
fun PhoneGreetingSlot(hour: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().testTag("greetingSlot")) {
        Text(text = greetingFor(hour))
    }
}
