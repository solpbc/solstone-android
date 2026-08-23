// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import java.time.LocalTime

// INTERIM: owner-facing accessible name for the up control; pending copy ruling.
private const val UP_CONTENT_DESCRIPTION = "up"

@Composable
fun PhoneObserverScreen(
    loadState: LoadState<SourcesReadModel>,
    status: PhoneStatusModel,
    waiting: List<app.solstone.observer.harness.SourceStatus> = emptyList(),
    onToggle: (String, SourceWish) -> Unit,
    modifier: Modifier = Modifier,
    initial: PhoneRouteStack = PhoneRouteStack.Empty,
) {
    var paneStates by rememberPaneStates()
    var detailStack by rememberPhoneRouteStack(initial)
    val hour = remember { LocalTime.now().hour }
    val minWidthDp = currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)
        .windowSizeClass
        .minWidthDp
    val widthClass = classifyWindowWidth(minWidthDp)
    val statusOpen = paneStates.isOpen(PhonePane.STATUS)
    val top = detailStack.toList().lastOrNull()
    val popDetail = {
        val remaining = detailStack.toList().dropLast(1)
        detailStack = remaining.fold(PhoneRouteStack.Empty) { stack, route ->
            if (stack.depth == 0) stack.showInDetail(route) else stack.pushInDetail(route)
        }
    }
    val popsDetail = resolveBack(paneStates, detailStack, widthClass).popsDetail
    PhoneShell(
        title = {
            val text = top?.let(::headingText)
            if (text != null) {
                Text(
                    text = text,
                    modifier = Modifier.semantics { heading() },
                )
            }
        },
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
        navigationIcon = {
            if (popsDetail) {
                IconButton(
                    onClick = popDetail,
                    modifier = Modifier.testTag("phoneUp"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.phone_navigation_up),
                        contentDescription = UP_CONTENT_DESCRIPTION,
                    )
                }
            }
        },
        content = { paddingValues ->
            if (top == null) {
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
            } else {
                val paneModifier = modifier.padding(paddingValues)
                when (top) {
                    PhoneRoute.AboutSolstone -> PhoneAboutPane(
                        onOpenLicences = {
                            detailStack = detailStack.pushInDetail(PhoneRoute.Licences)
                        },
                        modifier = paneModifier,
                    )
                    PhoneRoute.Licences -> PhoneLicencesPane(modifier = paneModifier)
                    PhoneRoute.RouteA,
                    PhoneRoute.RouteB,
                    PhoneRoute.RouteC,
                    PhoneRoute.RouteCChild,
                    is PhoneRoute.SourceDetail -> {
                        Box(
                            paneModifier.semantics { paneTitle = top.paneTitle },
                        )
                    }
                }
            }
        },
    )
    PhoneBackLadder(
        paneStates = paneStates,
        detailStack = detailStack,
        widthClass = widthClass,
        onClosePane = { pane -> paneStates = paneStates.close(pane) },
        onPopDetail = popDetail,
    )
}
