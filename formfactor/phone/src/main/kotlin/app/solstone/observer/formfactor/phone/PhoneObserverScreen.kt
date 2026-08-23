// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import java.time.LocalTime

@Composable
fun PhoneObserverScreen(
    loadState: LoadState<SourcesReadModel>,
    status: PhoneStatusModel,
    waiting: List<app.solstone.observer.harness.SourceStatus> = emptyList(),
    onToggle: (String, SourceWish) -> Unit,
    modifier: Modifier = Modifier,
) {
    var paneStates by rememberPaneStates()
    var detailStack by rememberPhoneRouteStack()
    val hour = remember { LocalTime.now().hour }
    val minWidthDp = currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)
        .windowSizeClass
        .minWidthDp
    val widthClass = classifyWindowWidth(minWidthDp)
    val statusOpen = paneStates.isOpen(PhonePane.STATUS)
    PhoneShell(
        title = {},
        statusAction = {
            Box {
                PhoneStatusPill(
                    model = status,
                    onClick = {
                        paneStates = if (statusOpen) {
                            paneStates.close(PhonePane.STATUS)
                        } else {
                            paneStates.open(PhonePane.STATUS)
                        }
                    },
                )
                if (statusOpen) {
                    PhoneStatusPane(
                        model = status,
                        waiting = waiting,
                        onDismiss = { paneStates = paneStates.close(PhonePane.STATUS) },
                        onOpenSource = { id ->
                            paneStates = paneStates.close(PhonePane.STATUS)
                            detailStack = detailStack.showInDetail(PhoneRoute.SourceDetail(id))
                        },
                    )
                }
            }
        },
        journalMark = {},
        content = { paddingValues ->
            PhoneDeck(
                loadState = loadState,
                contentPadding = paddingValues,
                widthClass = widthClass,
                paneOpen = statusOpen,
                onOpenSource = { id ->
                    detailStack = detailStack.showInDetail(PhoneRoute.SourceDetail(id))
                },
                onToggle = onToggle,
                hour = hour,
                modifier = modifier,
            )
        },
    )
    PhoneBackLadder(
        paneStates = paneStates,
        detailStack = detailStack,
        widthClass = widthClass,
        onClosePane = { pane -> paneStates = paneStates.close(pane) },
        onPopDetail = {
            val remaining = detailStack.toList().dropLast(1)
            detailStack = remaining.fold(PhoneRouteStack.Empty) { stack, route ->
                if (stack.depth == 0) stack.showInDetail(route) else stack.pushInDetail(route)
            }
        },
    )
}
