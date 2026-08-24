// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

@Composable
fun PhoneImportPane(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .semantics { paneTitle = PhoneRoute.Import.paneTitle },
    ) {
        PhoneImportRow(
            label = "photos",
            subLine = "pick from your library",
            testTag = "importRow-photos",
        )
        PhoneImportRow(
            label = "files",
            subLine = "documents, audio, PDFs",
            testTag = "importRow-files",
        )
        PhoneImportRow(
            label = "recently imported",
            subLine = null,
            testTag = "importRow-recentlyImported",
        )
        Text(
            text = "in your journal",
            modifier = Modifier.testTag("importReceipt"),
        )
    }
}

@Composable
private fun PhoneImportRow(
    label: String,
    subLine: String?,
    testTag: String,
) {
    Column(Modifier.testTag(testTag)) {
        Text(label)
        if (subLine != null) {
            Text(subLine)
        }
    }
}
