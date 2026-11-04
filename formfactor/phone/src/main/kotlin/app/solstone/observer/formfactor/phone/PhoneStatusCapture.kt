// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.core.pl.ClaimedJournalMark
import app.solstone.core.pl.UnknownJournalSighting
import app.solstone.core.pl.journalMarkFromJid
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish

enum class PhoneStatusCapture {
    LOADING,
    FAILED,
    UNPAIRED,
    PAIRED_OFFLINE,
    PAIRED_CAUGHT_UP,
    PAIRED_AWAITING_MARK,
    PAIRED_UNKNOWN_JOURNALS,
    PAIRED_REFUSED,
}

fun decodePhoneStatusCapture(raw: String?): PhoneStatusCapture? = when (raw) {
    "loading" -> PhoneStatusCapture.LOADING
    "failed" -> PhoneStatusCapture.FAILED
    "unpaired" -> PhoneStatusCapture.UNPAIRED
    "paired-offline" -> PhoneStatusCapture.PAIRED_OFFLINE
    "paired-caught-up" -> PhoneStatusCapture.PAIRED_CAUGHT_UP
    "paired-awaiting-mark" -> PhoneStatusCapture.PAIRED_AWAITING_MARK
    "paired-unknown-journals" -> PhoneStatusCapture.PAIRED_UNKNOWN_JOURNALS
    "paired-refused" -> PhoneStatusCapture.PAIRED_REFUSED
    else -> null
}

fun resolvePhoneCaptureWidthDp(raw: String?, debuggable: Boolean): Int? {
    if (!debuggable) return null
    return when (raw) {
        "compact" -> 599
        "wide" -> 800
        else -> null
    }
}

fun resolvePhoneStatusCapture(
    raw: String?,
    debuggable: Boolean,
): LoadState<PhoneStatusSnapshot>? {
    if (!debuggable) return null
    return when (decodePhoneStatusCapture(raw)) {
        PhoneStatusCapture.LOADING -> LoadState.Loading
        PhoneStatusCapture.FAILED -> LoadState.Failed(CapturedPhoneStatusFailure)
        PhoneStatusCapture.UNPAIRED -> capturedStatusState(paired = false, online = false, pendingCount = 0, awaitingMarkConfirmation = false)
        PhoneStatusCapture.PAIRED_OFFLINE -> capturedStatusState(paired = true, online = false, pendingCount = 1, awaitingMarkConfirmation = false)
        PhoneStatusCapture.PAIRED_CAUGHT_UP -> capturedStatusState(paired = true, online = true, pendingCount = 0, awaitingMarkConfirmation = false)
        PhoneStatusCapture.PAIRED_AWAITING_MARK -> capturedStatusState(paired = true, online = true, pendingCount = 0, awaitingMarkConfirmation = true)
        PhoneStatusCapture.PAIRED_UNKNOWN_JOURNALS -> capturedStatusState(
            paired = true,
            online = true,
            pendingCount = 0,
            unknownJournals = capturedUnknownJournals(),
        )
        PhoneStatusCapture.PAIRED_REFUSED -> capturedStatusState(paired = true, online = false, pendingCount = 2, refused = true)
        null -> null
    }
}

private fun capturedStatusState(
    paired: Boolean,
    online: Boolean,
    pendingCount: Int,
    awaitingMarkConfirmation: Boolean = false,
    unknownJournals: List<UnknownJournalSighting> = emptyList(),
    refused: Boolean = false,
): LoadState<PhoneStatusSnapshot> {
    val waiting = if (pendingCount > 0) {
        listOf(SourceStatus("audio", SourceWish.On, SourceState.ON, ReasonCode.NONE))
    } else {
        emptyList()
    }
    return LoadState.Loaded(
        PhoneStatusSnapshot(
            status = PhoneStatusModel(
                paired = paired,
                online = online,
                pendingCount = pendingCount,
                hasContentPending = waiting.isNotEmpty(),
                awaitingMarkConfirmation = awaitingMarkConfirmation,
                recoveryCompleted = true,
                audioAwaitingCustody = false,
                unknownJournals = unknownJournals,
                refused = refused,
            ),
            waiting = waiting,
        ),
    )
}

/** One sighting of each shape: a claim at an address, no claim at an address, a claim through the relay. */
private fun capturedUnknownJournals(): List<UnknownJournalSighting> {
    val claim = "f30ed159-ef46-8e9c-913f-e49f0fe7d201"
    val mark: ClaimedJournalMark = runCatching { journalMarkFromJid(claim) }.fold(
        onSuccess = { ClaimedJournalMark.Derived(it) },
        onFailure = { ClaimedJournalMark.Unavailable },
    )
    return listOf(
        UnknownJournalSighting("192.0.2.10:21000", claim, mark),
        UnknownJournalSighting("192.0.2.11:21000", null, ClaimedJournalMark.NotPresented),
        UnknownJournalSighting(null, claim, mark),
    )
}

private object CapturedPhoneStatusFailure : IllegalStateException()
