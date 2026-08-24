// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.collect

/**
 * Installs the three back handlers unconditionally. Rung 3 is the absence of an
 * enabled handler. This composable instantiates no drawer, sheet, popup, or
 * Material surface.
 */
@Composable
fun PhoneBackLadder(
    paneStates: PaneStates,
    detailStack: PhoneRouteStack,
    widthClass: WidthClass,
    onClosePane: (PhonePane) -> Unit,
    onPopDetail: () -> Unit,
) {
    val outcome = resolveBack(paneStates, detailStack, widthClass)
    PredictiveBackHandler(enabled = outcome.closesPane(PhonePane.JOURNAL)) { progress ->
        progress.collect()
        onClosePane(PhonePane.JOURNAL)
    }
    PredictiveBackHandler(enabled = outcome.closesPane(PhonePane.STATUS)) { progress ->
        progress.collect()
        onClosePane(PhonePane.STATUS)
    }
    BackHandler(enabled = outcome.popsDetail) {
        onPopDetail()
    }
}
