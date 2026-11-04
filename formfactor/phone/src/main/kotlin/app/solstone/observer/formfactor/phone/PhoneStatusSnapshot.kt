// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.core.pl.UnknownJournalSighting
import app.solstone.observer.harness.HarnessBacklogStatus
import app.solstone.observer.harness.HarnessPlStatus
import app.solstone.observer.harness.PresentedConnection
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.journalRefusesThisDevice

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
    gaps: PhoneGapStatus = PhoneGapStatus(),
    unknownJournals: List<UnknownJournalSighting> = emptyList(),
): PhoneStatusSnapshot {
    val paired = backlog.plStatus != HarnessPlStatus.NotPaired
    val refused = backlog.plStatus.journalRefusesThisDevice()
    // The connection is the presenter's, the one every surface shows. A journal that answers is
    // heard, a 503 included. One that refuses this phone (401, 403, a stop) has not accepted it, so
    // it is never connected: the refusal shows instead.
    val online = paired && !refused && backlog.connection == PresentedConnection.CONNECTED
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
            connecting = paired && !refused && backlog.connection == PresentedConnection.CONNECTING,
            refused = refused,
            pendingCount = backlog.pendingCount,
            hasContentPending = backlog.pendingSourceIds.isNotEmpty(),
            awaitingMarkConfirmation = awaitingMarkConfirmation,
            recoveryCompleted = recoveryCompleted,
            audioAwaitingCustody = audioAwaitingCustody,
            gapLosses = gaps.losses,
            unknownPresent = gaps.unknownPresent,
            unknownUnseen = gaps.unknownUnseen,
            unseenUnknownKeys = gaps.unseenUnknownKeys,
            gateOpen = gaps.gateOpen,
            gapTechnicalDetails = gaps.technicalDetails,
            journalVersion = backlog.journalVersion,
            calendarHeldForJournal = backlog.calendarHeldForJournal,
            unknownJournals = unknownJournals,
        ),
        waiting = waiting,
    )
}
