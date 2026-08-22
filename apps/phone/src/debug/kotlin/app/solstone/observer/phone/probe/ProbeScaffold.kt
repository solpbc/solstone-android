// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.probe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ProbeScaffold(
    measures: String,
    drive: String,
    prediction: String? = null,
    gaps: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text("MEASURES")
        Text(measures)
        Text("DRIVE")
        Text(drive)
        if (prediction != null) {
            Text("PREDICTION")
            Text(prediction)
        }
        if (gaps != null) {
            Text("GAPS")
            Text(gaps)
        }
        content()
    }
}
