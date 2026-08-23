// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

@Composable
fun PhoneLicencesPane(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .semantics(mergeDescendants = true) {
                paneTitle = PhoneRoute.Licences.paneTitle
            },
    ) {
        Text("AGPL-3.0-only")
    }
}
