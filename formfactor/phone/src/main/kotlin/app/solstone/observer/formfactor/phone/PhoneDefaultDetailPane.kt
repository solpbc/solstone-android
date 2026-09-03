// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag("phoneDefaultDetailLoading"),
                )
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
    PhonePaneScaffold(
        modifier = modifier.semantics { paneTitle = spokenPaneTitle(PhonePane.STATUS) },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        Text(
            text = "status unavailable",
            modifier = Modifier.testTag("phoneDefaultDetailFailed"),
        )
        onRefreshStatus?.let { refresh ->
            TextButton(
                onClick = refresh,
                modifier = Modifier.testTag("phoneDefaultDetailFailedRetry"),
            ) {
                Text("try again")
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
    PhonePaneScaffold(
        modifier = modifier.semantics { paneTitle = spokenPaneTitle(PhonePane.STATUS) },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PhonePairedStatusContent(
            model = snapshot.status,
            waiting = snapshot.waiting,
            onOpenSource = onOpenSource,
        )
    }
}
