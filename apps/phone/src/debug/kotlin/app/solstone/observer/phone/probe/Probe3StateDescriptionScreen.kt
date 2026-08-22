// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.probe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Four accessibility cases for stateDescription and polite liveRegion. No spoken-announcement API.
 * Drive: open this screen, then use the increment button or wait for the 2s ticker.
 *   open: adb shell am start -n app.solstone.observer.phone/app.solstone.observer.phone.probe.ProbeIndexActivity
 */
@Composable
fun Probe3StateDescriptionScreen() {
    var value by remember { mutableIntStateOf(0) }
    val focusB = remember { FocusRequester() }
    val focusPark = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            value += 1
        }
    }
    ProbeScaffold(
        measures = "unfocused stateDescription; focused stateDescription (positive control); polite liveRegion; both at once",
        drive = "Increment button or the 2s ticker. Use focus B / park focus to move focus.",
    ) {
        Button(onClick = { value += 1 }) { Text("increment") }
        Text("value=$value")
        Text("A unfocused stateDescription")
        Box(
            Modifier
                .padding(8.dp)
                .semantics {
                    contentDescription = "A unfocused stateDescription"
                    stateDescription = "value=$value"
                },
        ) { Text("A node") }
        Text("B focused stateDescription (positive control)")
        Box(
            Modifier
                .padding(8.dp)
                .focusRequester(focusB)
                .focusable()
                .clickable { focusB.requestFocus() }
                .semantics {
                    contentDescription = "B focused stateDescription (positive control)"
                    stateDescription = "value=$value"
                },
        ) { Text("B node") }
        Text("park focus here")
        Box(
            Modifier
                .padding(8.dp)
                .focusRequester(focusPark)
                .focusable()
                .clickable { focusPark.requestFocus() }
                .semantics { contentDescription = "park focus here" },
        ) { Text("park node") }
        Button(onClick = { focusB.requestFocus() }) { Text("focus B") }
        Button(onClick = { focusPark.requestFocus() }) { Text("park focus") }
        Text("C polite liveRegion")
        Box(
            Modifier
                .padding(8.dp)
                .semantics {
                    contentDescription = "C polite liveRegion value=$value"
                    liveRegion = LiveRegionMode.Polite
                },
        ) { Text("C node") }
        Text("D stateDescription + polite liveRegion")
        Box(
            Modifier
                .padding(8.dp)
                .semantics {
                    contentDescription = "D stateDescription + polite liveRegion"
                    stateDescription = "value=$value"
                    liveRegion = LiveRegionMode.Polite
                },
        ) { Text("D node") }
    }
}
