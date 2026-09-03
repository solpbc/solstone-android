// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.solstone.observer.harness.LoadState

sealed interface PhoneDefaultDetailStatus {
    data object Loading : PhoneDefaultDetailStatus

    data object Failed : PhoneDefaultDetailStatus

    data object Unpaired : PhoneDefaultDetailStatus

    data class Paired(val snapshot: PhoneStatusSnapshot) : PhoneDefaultDetailStatus
}

fun phoneDefaultDetailStatusOf(
    statusState: LoadState<PhoneStatusSnapshot>,
): PhoneDefaultDetailStatus = when (statusState) {
    LoadState.Loading -> PhoneDefaultDetailStatus.Loading
    is LoadState.Failed -> PhoneDefaultDetailStatus.Failed
    is LoadState.Loaded -> if (statusState.value.status.paired) {
        PhoneDefaultDetailStatus.Paired(statusState.value)
    } else {
        PhoneDefaultDetailStatus.Unpaired
    }
}

@Composable
internal fun PhoneDefaultDetailPane(
    status: PhoneDefaultDetailStatus,
    onConnectJournal: () -> Unit,
    onOpenSource: (String) -> Unit,
    onRefreshStatus: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("phoneDefaultDetail"),
    ) {
        when (status) {
            PhoneDefaultDetailStatus.Loading -> {
                PhoneDefaultStatusPane(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .testTag("phoneDefaultDetailLoading"),
                        )
                    }
                }
            }
            PhoneDefaultDetailStatus.Failed -> PhoneDefaultDetailFailure(
                onRefreshStatus = onRefreshStatus,
                modifier = Modifier.fillMaxSize(),
            )
            PhoneDefaultDetailStatus.Unpaired -> PhoneYourJournalPane(
                onConnectJournal = onConnectJournal,
                modifier = Modifier.fillMaxSize(),
            )
            is PhoneDefaultDetailStatus.Paired -> PhoneDefaultDetailPaired(
                snapshot = status.snapshot,
                onOpenSource = onOpenSource,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PhoneDefaultDetailFailure(
    onRefreshStatus: (() -> Unit)?,
    modifier: Modifier,
) {
    PhoneDefaultStatusPane(modifier = modifier) {
        Text(
            text = "status unavailable",
            modifier = Modifier.testTag("phoneDefaultDetailFailed"),
        )
        onRefreshStatus?.let { refresh ->
            TextButton(
                onClick = refresh,
                modifier = Modifier.testTag("phoneDefaultDetailFailedRetry"),
            ) {
                // ⛔ `try now`, not `try again`: iOS ships this control as `try now`
                    // (SourceVocabulary), and a second word for one control on a
                    // surface § 6 makes shared canon is the exact § 5.1 defect.
                    Text("try now")
            }
        }
    }
}

@Composable
private fun PhoneDefaultDetailPaired(
    snapshot: PhoneStatusSnapshot,
    onOpenSource: (String) -> Unit,
    modifier: Modifier,
) {
    PhoneDefaultStatusPane(modifier = modifier) {
        PhonePairedStatusContent(
            model = snapshot.status,
            waiting = snapshot.waiting,
            onOpenSource = onOpenSource,
        )
    }
}

@Composable
private fun PhoneDefaultStatusPane(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    PhonePaneScaffold(
        modifier = modifier.semantics { paneTitle = spokenPaneTitle(PhonePane.STATUS) },
    ) {
        PaneSectionTitle(
            text = headingText(PhonePane.STATUS).orEmpty(),
            modifier = Modifier.testTag("phoneDefaultStatusHeading"),
        )
        content()
    }
}
