// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.ReasonCode
import app.solstone.observer.formfactor.phone.PhoneWidgetLineKind.DIAGNOSIS
import app.solstone.observer.formfactor.phone.PhoneWidgetLineKind.HEADER
import app.solstone.observer.formfactor.phone.PhoneWidgetLineKind.NOTICE
import app.solstone.observer.formfactor.phone.PhoneWidgetLineKind.SYNC
import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneObserverWidgetLayoutTest {
    // Every line takes one row unless a test says otherwise.
    private val oneRowEach = PhoneWidgetTextMeasure { _, _ -> 1 }

    @Test
    fun withRoomForEverythingTheLinesKeepDisplayOrder() {
        val lines = fitPhoneWidgetLines(faultHeldInterrupted(), "audio", maxLines = 10, measure = oneRowEach)

        assertEquals(listOf(HEADER, DIAGNOSIS, SYNC, NOTICE, NOTICE), lines.map { it.kind })
    }

    @Test
    fun twoRowsWithoutAFaultKeepTheHeaderAndTheSyncText() {
        val lines = fitPhoneWidgetLines(heldInterrupted(), "audio", maxLines = 2, measure = oneRowEach)

        assertEquals(listOf(HEADER, SYNC), lines.map { it.kind })
    }

    @Test
    fun oneRowWithoutAFaultKeepsTheSyncText() {
        val lines = fitPhoneWidgetLines(heldInterrupted(), "audio", maxLines = 1, measure = oneRowEach)

        assertEquals(listOf(SYNC), lines.map { it.kind })
    }

    @Test
    fun aFaultOutranksTheSyncTextAndBringsItsReason() {
        assertEquals(
            listOf(HEADER),
            fitPhoneWidgetLines(faultHeldInterrupted(), "audio", maxLines = 1, measure = oneRowEach).map { it.kind },
        )
        assertEquals(
            listOf(HEADER, DIAGNOSIS),
            fitPhoneWidgetLines(faultHeldInterrupted(), "audio", maxLines = 2, measure = oneRowEach).map { it.kind },
        )
    }

    @Test
    fun aLineThatDoesNotFitWholeIsDroppedAndAShorterOneAfterItStillShows() {
        val tallDiagnosis = PhoneWidgetTextMeasure { _, kind -> if (kind == DIAGNOSIS) 3 else 1 }
        val model = heldInterrupted().copy(diagnosis = "a long reason")

        val lines = fitPhoneWidgetLines(model, "audio", maxLines = 4, measure = tallDiagnosis)

        assertEquals(listOf(HEADER, SYNC, NOTICE, NOTICE), lines.map { it.kind })
        assertEquals(listOf(1, 1, 1, 1), lines.map { it.lines })
    }

    @Test
    fun aNoticeSubLineNeverShowsWithoutItsLead() {
        // The lead wraps to two rows and the sub-line takes one; with one row left, neither shows.
        val leadWraps = PhoneWidgetTextMeasure { text, kind ->
            if (kind == NOTICE && text == heldInterrupted().syncDetail!!.lines().first()) 2 else 1
        }

        val lines = fitPhoneWidgetLines(heldInterrupted(), "audio", maxLines = 3, measure = leadWraps)

        assertEquals(listOf(HEADER, SYNC), lines.map { it.kind })
    }

    @Test
    fun aWrappedLineKeepsItsMeasuredRows() {
        val syncWraps = PhoneWidgetTextMeasure { _, kind -> if (kind == SYNC) 2 else 1 }

        val lines = fitPhoneWidgetLines(heldInterrupted(), "audio", maxLines = 3, measure = syncWraps)

        assertEquals(listOf(HEADER, SYNC), lines.map { it.kind })
        assertEquals(listOf(1, 2), lines.map { it.lines })
    }

    @Test
    fun theFirstLineByPriorityShowsAloneWhenEvenItCannotFit() {
        val everythingWraps = PhoneWidgetTextMeasure { _, _ -> 3 }

        val lines = fitPhoneWidgetLines(heldInterrupted(), "audio", maxLines = 2, measure = everythingWraps)

        assertEquals(listOf(SYNC), lines.map { it.kind })
        assertEquals(2, lines.single().lines)
    }

    @Test
    fun noRoomAtAllStillSaysOneThing() {
        val lines = fitPhoneWidgetLines(faultHeldInterrupted(), "audio", maxLines = 0, measure = oneRowEach)

        assertEquals(listOf(HEADER), lines.map { it.kind })
    }

    @Test
    fun theHeaderNamesTheSourceAndCarriesTheStateWord() {
        val model = heldInterrupted()
        val header = phoneWidgetCandidateLines(model, "audio").first()

        assertEquals(HEADER, header.kind)
        assertEquals(true, header.text.startsWith("audio"))
        assertEquals(true, header.text.endsWith(model.stateWord))
    }

    @Test
    fun theSyncLineIsThePillTextUnchanged() {
        val model = heldInterrupted()
        val sync = phoneWidgetCandidateLines(model, "audio").single { it.kind == SYNC }

        assertEquals(statusPillText(heldStatus()), sync.text)
    }

    private fun heldStatus() = PhoneStatusModel(
        paired = true,
        online = true,
        pendingCount = 1,
        hasContentPending = true,
        awaitingMarkConfirmation = true,
        recoveryCompleted = true,
        audioAwaitingCustody = false,
        unresolvedAudioInterruption = true,
    )

    private fun heldInterrupted(): PhoneObserverWidgetModel {
        val status = heldStatus()
        val custody = audioCustodyLines(status).filter { it != statusPillText(status) }
        return PhoneObserverWidgetModel(
            audioChecked = true,
            audioWishOn = true,
            stateWord = sourceStateCopy(app.solstone.core.model.SourceState.ON),
            needsAttention = false,
            reason = ReasonCode.NONE,
            awaitingMarkConfirmation = true,
            pendingCount = 1,
            syncText = statusPillText(status),
            syncDetail = custody.joinToString("\n"),
            colors = emptySet(),
        )
    }

    private fun faultHeldInterrupted() = heldInterrupted().copy(
        audioChecked = false,
        stateWord = sourceStateCopy(app.solstone.core.model.SourceState.NEEDS_ATTENTION),
        needsAttention = true,
        reason = ReasonCode.PERMISSION_REVOKED,
        diagnosis = app.solstone.observer.harness.reasonDiagnosis(ReasonCode.PERMISSION_REVOKED),
    )
}
