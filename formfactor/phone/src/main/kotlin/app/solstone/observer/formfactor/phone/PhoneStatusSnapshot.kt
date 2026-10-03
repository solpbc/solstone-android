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
    awaitingMarkConfirmation: Boolean,
    recoveryCompleted: Boolean,
    audioAwaitingCustody: Boolean,
    unresolvedAudioInterruption: Boolean,
    unresolvedOtherInterruption: Boolean = false,
    unresolvedUnknownRecovery: Boolean = false,
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
            add(SourceStatus(sourceId, SourceWish.Off, SourceState.OFF, ReasonCode.NONE))
        }
    }
    return PhoneStatusSnapshot(
        status = PhoneStatusModel(
            paired = paired,
            online = online,
            pendingCount = backlog.pendingCount,
            hasContentPending = backlog.pendingSourceIds.isNotEmpty(),
            awaitingMarkConfirmation = awaitingMarkConfirmation,
            recoveryCompleted = recoveryCompleted,
            audioAwaitingCustody = audioAwaitingCustody,
            unresolvedAudioInterruption = unresolvedAudioInterruption,
            unresolvedOtherInterruption = unresolvedOtherInterruption,
            unresolvedUnknownRecovery = unresolvedUnknownRecovery,
            journalVersion = backlog.journalVersion,
        ),
        waiting = waiting,
    )
}
