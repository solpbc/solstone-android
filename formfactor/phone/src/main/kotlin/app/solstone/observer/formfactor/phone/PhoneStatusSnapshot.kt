// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.HarnessBacklogStatus
import app.solstone.observer.harness.HarnessPlStatus
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish

data class PhoneStatusSnapshot(
    val status: PhoneStatusModel,
    val waiting: List<SourceStatus>,
)

fun phoneStatusSnapshotOf(
    backlog: HarnessBacklogStatus,
    registered: List<SourceStatus>,
): PhoneStatusSnapshot {
    val (paired, online) = when (backlog.plStatus) {
        HarnessPlStatus.NotPaired -> false to false
        is HarnessPlStatus.PairedButUnreachable -> true to false
        is HarnessPlStatus.Reachable -> true to true
    }
    val registeredById = registered.associateBy { it.sourceId }
    val pendingIds = backlog.pendingSourceIds.toSet()
    val waiting = buildList {
        registered.forEach { status ->
            if (status.sourceId in pendingIds) add(status)
        }
        (pendingIds - registeredById.keys).sorted().forEach { sourceId ->
            // The pane reads only sourceId; the remaining required fields have no unknown member and are never rendered.
            add(SourceStatus(sourceId, SourceWish.Off, SourceState.OFF, ReasonCode.NONE))
        }
    }
    return PhoneStatusSnapshot(
        status = PhoneStatusModel(
            paired = paired,
            online = online,
            pendingCount = backlog.pendingCount,
            hasContentPending = backlog.pendingSourceIds.isNotEmpty(),
        ),
        waiting = waiting,
    )
}
