// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

enum class PairedStatusLead { CAUGHT_UP, COUNT, MARK_LINE }

data class PairedStatusSummary(
    val lead: PairedStatusLead,
    val markLineFollowsCount: Boolean,
    val subLine: Boolean,
    val action: Boolean,
)

fun pairedStatusSummary(model: PhoneStatusModel): PairedStatusSummary? = when (statusPillKind(model)) {
    StatusPillKind.NOT_PAIRED -> null
    StatusPillKind.AWAITING_MARK_CONFIRMATION -> if (model.pendingCount == 0) {
        PairedStatusSummary(
            lead = PairedStatusLead.MARK_LINE,
            markLineFollowsCount = false,
            subLine = true,
            action = true,
        )
    } else {
        PairedStatusSummary(
            lead = PairedStatusLead.COUNT,
            markLineFollowsCount = true,
            subLine = true,
            action = true,
        )
    }
    StatusPillKind.CONNECTED -> PairedStatusSummary(
        lead = PairedStatusLead.CAUGHT_UP,
        markLineFollowsCount = false,
        subLine = true,
        action = false,
    )
    StatusPillKind.SYNCING -> PairedStatusSummary(
        lead = PairedStatusLead.COUNT,
        markLineFollowsCount = false,
        subLine = true,
        action = false,
    )
    StatusPillKind.OFFLINE -> PairedStatusSummary(
        lead = PairedStatusLead.COUNT,
        markLineFollowsCount = false,
        subLine = true,
        action = false,
    )
}
