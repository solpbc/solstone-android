// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

sealed interface BackOutcome {
    data class ClosePane(val pane: PhonePane) : BackOutcome
    data object PopDetail : BackOutcome
    data object FallThroughToSystem : BackOutcome
}

/**
 * Three-rung back decision: close an open pane (SHELF, else JOURNAL, else STATUS),
 * else pop a detail stack of depth 1 or more, else fall through to the system.
 *
 * [widthClass] is accepted so a later tablet layout can branch without changing
 * this signature. The current resolver does not branch on it.
 */
@Suppress("UNUSED_PARAMETER")
fun resolveBack(
    paneStates: PaneStates,
    detailStack: PhoneRouteStack,
    widthClass: WidthClass,
): BackOutcome {
    val pane = PhonePane.entries.firstOrNull { paneStates.isOpen(it) }
    if (pane != null) return BackOutcome.ClosePane(pane)
    if (detailStack.depth >= 1) return BackOutcome.PopDetail
    return BackOutcome.FallThroughToSystem
}

fun BackOutcome.closesPane(pane: PhonePane): Boolean =
    this is BackOutcome.ClosePane && this.pane == pane

val BackOutcome.popsDetail: Boolean
    get() = this is BackOutcome.PopDetail
