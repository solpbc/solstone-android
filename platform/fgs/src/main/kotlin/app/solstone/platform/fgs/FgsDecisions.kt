// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

import app.solstone.core.model.SourceState

data class ObserverStartCommandPlan(
    val initialNeedsAttention: Boolean,
    val dispatchRehydrate: Boolean,
    val postAttentionOn102: Boolean,
    val stopSelf: Boolean,
)

fun onStartCommandPlan(hasIntent: Boolean, hasRehydrator: Boolean): ObserverStartCommandPlan =
    ObserverStartCommandPlan(
        initialNeedsAttention = true,
        dispatchRehydrate = hasRehydrator,
        postAttentionOn102 = !hasIntent && !hasRehydrator,
        stopSelf = !hasIntent && !hasRehydrator,
    )

fun needsAttentionForState(state: SourceState): Boolean = state != SourceState.ON

fun startFailureDiagLine(exceptionClassName: String): String =
    "fgs start-failure exception=$exceptionClassName"
