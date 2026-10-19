// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.DirectPairLink
import app.solstone.core.pl.PairLinkAcceptance
import app.solstone.observer.harness.HarnessPairProbeResult
import app.solstone.observer.harness.PairAttemptOutcome
import app.solstone.observer.harness.PairLinkDispatchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhonePairingSessionTest {
    @Test
    fun externalArrivalRestoresPersistedHeldMarkWithoutStagingOrDialing() {
        for (session in listOf(
            PhonePairingSession.Closed,
            PhonePairingSession.Entry(null, false, null),
            PhonePairingSession.Scanning(null),
        )) {
            val step = routePhonePairIntent(PhonePairingState(session), "https://go.solstone.app/p#garbage", true)
            assertTrue(step.state.session is PhonePairingSession.Held)
            assertEquals("", (step.state.session as PhonePairingSession.Held).stagedCanonical)
            assertNull(step.dial)
        }
    }

    private val canonical = "https://go.solstone.app/p#FAKE"
    private val fakeParsed = DirectPairLink(
        candidates = listOf(DirectEndpoint("10.0.0.1", 7657)),
        nonce = "00",
        caFingerprintPrefix = ByteArray(16),
    )
    private val accepted = PairLinkAcceptance.Accepted(canonical, fakeParsed)
    private val rejected = PairLinkAcceptance.Rejected

    private val successfulOutcome = PairAttemptOutcome.Linked(
        result = HarnessPairProbeResult(
            handshakePinned = true,
            pairStatus = 200,
            statusStatus = 200,
            statusBody = "ok",
            homeLabel = "home",
            endpointHost = "10.0.0.1",
            endpointPort = 7657,
        ),
    )
    private val failedOutcome = PairAttemptOutcome.ExistingPairingActive

    @Test
    fun submitAcceptedTransitionsToEntryAndDoesNotDial() {
        val initial = PhonePairingState()
        val step = reducePhonePairing(initial, PhonePairingEvent.Submit(accepted))
        val session = step.state.session
        assertTrue(session is PhonePairingSession.Entry)
        assertEquals(canonical, session.stagedCanonical)
        assertFalse(session.invalidSubmit)
        assertNull(session.outcome)
        assertNull(step.dial)
        assertFalse(step.detachPreview)
    }

    @Test
    fun submitAcceptedFromScanningDetachesPreview() {
        val initial = PhonePairingState(session = PhonePairingSession.Scanning(stagedCanonical = null))
        val step = reducePhonePairing(initial, PhonePairingEvent.Submit(accepted))
        val session = step.state.session
        assertTrue(session is PhonePairingSession.Entry)
        assertEquals(canonical, session.stagedCanonical)
        assertTrue(step.detachPreview)
        assertNull(step.dial)
    }

    @Test
    fun submitRejectedFromClosedOrEntryFlagsInvalidAndDoesNotDial() {
        val closed = PhonePairingState()
        val stepClosed = reducePhonePairing(closed, PhonePairingEvent.Submit(rejected))
        val sessionClosed = stepClosed.state.session
        assertTrue(sessionClosed is PhonePairingSession.Entry)
        assertNull(sessionClosed.stagedCanonical)
        assertTrue(sessionClosed.invalidSubmit)
        assertNull(stepClosed.dial)

        val entry = PhonePairingState(session = PhonePairingSession.Entry(canonical, invalidSubmit = false, outcome = null))
        val stepEntry = reducePhonePairing(entry, PhonePairingEvent.Submit(rejected))
        val sessionEntry = stepEntry.state.session
        assertTrue(sessionEntry is PhonePairingSession.Entry)
        assertEquals(canonical, sessionEntry.stagedCanonical)
        assertTrue(sessionEntry.invalidSubmit)
        assertNull(stepEntry.dial)
    }

    @Test
    fun submitRejectedFromScanningRemainsScanningWithoutFlag() {
        val scanning = PhonePairingState(session = PhonePairingSession.Scanning(stagedCanonical = canonical))
        val step = reducePhonePairing(scanning, PhonePairingEvent.Submit(rejected))
        assertEquals(scanning, step.state)
        assertNull(step.dial)
        assertFalse(step.detachPreview)
    }

    @Test
    fun submitDuringAttemptingOrHeldIsNoOp() {
        val attempting = PhonePairingState(
            session = PhonePairingSession.Attempting(stagedCanonical = canonical, generation = 1),
            generation = 1,
        )
        val stepAttempting = reducePhonePairing(attempting, PhonePairingEvent.Submit(accepted))
        assertEquals(attempting, stepAttempting.state)
        assertNull(stepAttempting.dial)

        val held = PhonePairingState(
            session = PhonePairingSession.Held(stagedCanonical = canonical, generation = 1, mismatchFailure = false),
            generation = 1,
        )
        val stepHeld = reducePhonePairing(held, PhonePairingEvent.Submit(accepted))
        assertEquals(held, stepHeld.state)
        assertNull(stepHeld.dial)
    }

    @Test
    fun openScanAndCloseScanTransitions() {
        val closed = PhonePairingState()
        val stepOpen = reducePhonePairing(closed, PhonePairingEvent.OpenScan)
        val sessionOpen = stepOpen.state.session
        assertTrue(sessionOpen is PhonePairingSession.Scanning)
        assertNull(sessionOpen.stagedCanonical)
        assertNull(stepOpen.dial)

        val stepClose = reducePhonePairing(stepOpen.state, PhonePairingEvent.CloseScan)
        val sessionClose = stepClose.state.session
        assertTrue(sessionClose is PhonePairingSession.Entry)
        assertNull(sessionClose.stagedCanonical)
        assertTrue(stepClose.detachPreview)
    }

    @Test
    fun connectFromEntryDialsOnceAndIncrementsGenerationMonotonically() {
        val entry = PhonePairingState(
            session = PhonePairingSession.Entry(canonical, invalidSubmit = false, outcome = null),
            generation = 0,
        )
        val step = reducePhonePairing(entry, PhonePairingEvent.Connect)
        assertEquals(canonical, step.dial)
        assertEquals(1L, step.state.generation)
        val session = step.state.session
        assertTrue(session is PhonePairingSession.Attempting)
        assertEquals(canonical, session.stagedCanonical)
        assertEquals(1L, session.generation)

        // Second connect while Attempting is a no-op
        val secondStep = reducePhonePairing(step.state, PhonePairingEvent.Connect)
        assertNull(secondStep.dial)
        assertEquals(step.state, secondStep.state)
    }

    @Test
    fun connectWithoutStagedOrInWrongStateIsNoOp() {
        val emptyEntry = PhonePairingState(
            session = PhonePairingSession.Entry(null, invalidSubmit = false, outcome = null),
            generation = 0,
        )
        val step = reducePhonePairing(emptyEntry, PhonePairingEvent.Connect)
        assertNull(step.dial)
        assertEquals(emptyEntry, step.state)

        val closed = PhonePairingState()
        val stepClosed = reducePhonePairing(closed, PhonePairingEvent.Connect)
        assertNull(stepClosed.dial)
        assertEquals(closed, stepClosed.state)
    }

    @Test
    fun attemptFinishedBusyReturnsToEntryWithoutAutomaticDial() {
        val state = PhonePairingState(
            session = PhonePairingSession.Attempting(canonical, 1L),
            generation = 1L,
        )
        val step = reducePhonePairing(state, PhonePairingEvent.AttemptFinished(1L, PairLinkDispatchResult.Busy))
        assertNull(step.dial)
        val session = step.state.session
        assertTrue(session is PhonePairingSession.Entry)
        assertEquals(canonical, session.stagedCanonical)
        assertFalse(session.invalidSubmit)
        assertNull(session.outcome)
    }

    @Test
    fun attemptFinishedSuccessTransitionsToHeld() {
        val state = PhonePairingState(
            session = PhonePairingSession.Attempting(canonical, 1L),
            generation = 1L,
        )
        val step = reducePhonePairing(
            state,
            PhonePairingEvent.AttemptFinished(1L, PairLinkDispatchResult.Attempted(successfulOutcome)),
        )
        assertNull(step.dial)
        val session = step.state.session
        assertTrue(session is PhonePairingSession.Held)
        assertEquals(canonical, session.stagedCanonical)
        assertEquals(1L, session.generation)
        assertFalse(session.mismatchFailure)
    }

    @Test
    fun attemptFinishedFailureStoresOutcomeInEntry() {
        val state = PhonePairingState(
            session = PhonePairingSession.Attempting(canonical, 1L),
            generation = 1L,
        )
        val step = reducePhonePairing(
            state,
            PhonePairingEvent.AttemptFinished(1L, PairLinkDispatchResult.Attempted(failedOutcome)),
        )
        assertNull(step.dial)
        val session = step.state.session
        assertTrue(session is PhonePairingSession.Entry)
        assertEquals(canonical, session.stagedCanonical)
        assertEquals(failedOutcome, session.outcome)
    }

    @Test
    fun attemptFinishedStaleGenerationIsNoOp() {
        val state = PhonePairingState(
            session = PhonePairingSession.Attempting(canonical, 2L),
            generation = 2L,
        )
        val step = reducePhonePairing(
            state,
            PhonePairingEvent.AttemptFinished(1L, PairLinkDispatchResult.Attempted(successfulOutcome)),
        )
        assertEquals(state, step.state)
        assertNull(step.dial)
    }

    @Test
    fun mismatchFinishedClearedKeepsTheMarkViewUpAsDisconnected() {
        val held = PhonePairingState(
            session = PhonePairingSession.Held(canonical, 1L, mismatchFailure = false),
            generation = 1L,
        )
        val step = reducePhonePairing(held, PhonePairingEvent.MismatchFinished(1L, cleared = true))
        val session = step.state.session
        assertTrue(session is PhonePairingSession.Held)
        assertTrue(session.disconnected)
        assertFalse(session.mismatchFailure)
        assertEquals(1L, step.state.generation)
        assertNull(step.dial)
        assertFalse(step.returnToShell)
    }

    @Test
    fun scanAgainAfterADisconnectOpensTheScannerButNotWhilePairingIsHeld() {
        val stillHeld = PhonePairingState(
            session = PhonePairingSession.Held(canonical, 1L, mismatchFailure = false),
            generation = 1L,
        )
        assertEquals(stillHeld, reducePhonePairing(stillHeld, PhonePairingEvent.OpenScan).state)

        val disconnected = reducePhonePairing(stillHeld, PhonePairingEvent.MismatchFinished(1L, cleared = true)).state
        val step = reducePhonePairing(disconnected, PhonePairingEvent.OpenScan)
        assertEquals(PhonePairingSession.Scanning(stagedCanonical = null), step.state.session)
        assertNull(step.dial)
    }

    @Test
    fun aLinkArrivingAfterADisconnectIsStagedAsFromEntry() {
        val disconnected = PhonePairingState(
            session = PhonePairingSession.Held(canonical, 1L, mismatchFailure = false, disconnected = true),
            generation = 1L,
        )
        val staged = reducePhonePairing(disconnected, PhonePairingEvent.Submit(accepted))
        assertEquals(PhonePairingSession.Entry(canonical, invalidSubmit = false, outcome = null), staged.state.session)
        val rejected = reducePhonePairing(disconnected, PhonePairingEvent.Submit(PairLinkAcceptance.Rejected))
        assertEquals(PhonePairingSession.Entry(null, invalidSubmit = true, outcome = null), rejected.state.session)
    }

    @Test
    fun mismatchFinishedLocalFailureRetainsHeldWithFlag() {
        val held = PhonePairingState(
            session = PhonePairingSession.Held(canonical, 1L, mismatchFailure = false),
            generation = 1L,
        )
        val step = reducePhonePairing(held, PhonePairingEvent.MismatchFinished(1L, cleared = false))
        val session = step.state.session
        assertTrue(session is PhonePairingSession.Held)
        assertTrue(session.mismatchFailure)
        assertEquals(1L, session.generation)
        assertEquals(canonical, session.stagedCanonical)
    }

    @Test
    fun mismatchFinishedStaleGenerationIsNoOp() {
        val held = PhonePairingState(
            session = PhonePairingSession.Held(canonical, 2L, mismatchFailure = false),
            generation = 2L,
        )
        val step = reducePhonePairing(held, PhonePairingEvent.MismatchFinished(1L, cleared = true))
        assertEquals(held, step.state)
    }

    @Test
    fun confirmTransitionsToClosedAndReturnsToShell() {
        val held = PhonePairingState(
            session = PhonePairingSession.Held(canonical, 1L, mismatchFailure = false),
            generation = 1L,
        )
        val step = reducePhonePairing(held, PhonePairingEvent.Confirm)
        assertEquals(PhonePairingSession.Closed, step.state.session)
        assertEquals(1L, step.state.generation)
        assertTrue(step.returnToShell)
        assertNull(step.dial)
    }

    @Test
    fun dismissFromAnyActiveStateClosesAndReturnsToShell() {
        val states = listOf(
            PhonePairingState(session = PhonePairingSession.Scanning(canonical), generation = 1L),
            PhonePairingState(session = PhonePairingSession.Entry(canonical, false, null), generation = 1L),
            PhonePairingState(session = PhonePairingSession.Attempting(canonical, 1L), generation = 1L),
            PhonePairingState(session = PhonePairingSession.Held(canonical, 1L, false), generation = 1L),
        )
        for (st in states) {
            val step = reducePhonePairing(st, PhonePairingEvent.Dismiss)
            assertEquals(PhonePairingSession.Closed, step.state.session)
            assertTrue(step.returnToShell)
            assertNull(step.dial)
                if (st.session is PhonePairingSession.Scanning) {
                assertTrue(step.detachPreview)
            } else {
                assertFalse(step.detachPreview)
            }
        }
    }

    @Test
    fun dismissThenConnectUsesHigherGeneration() {
        var state = PhonePairingState(
            session = PhonePairingSession.Entry(canonical, false, null),
            generation = 0L,
        )
        // Connect -> Attempting (gen 1)
        val step1 = reducePhonePairing(state, PhonePairingEvent.Connect)
        assertEquals(1L, step1.state.generation)

        // Dismiss -> Closed (gen 1 preserved)
        val step2 = reducePhonePairing(step1.state, PhonePairingEvent.Dismiss)
        assertEquals(PhonePairingSession.Closed, step2.state.session)
        assertEquals(1L, step2.state.generation)

        // Stage link again -> Entry (gen 1 preserved)
        val step3 = reducePhonePairing(step2.state, PhonePairingEvent.Submit(accepted))
        assertEquals(1L, step3.state.generation)

        // Connect -> Attempting (gen 2)
        val step4 = reducePhonePairing(step3.state, PhonePairingEvent.Connect)
        assertEquals(2L, step4.state.generation)
        val session4 = step4.state.session
        assertTrue(session4 is PhonePairingSession.Attempting)
        assertEquals(2L, session4.generation)
    }

    @Test
    fun openMarkTransitionsToHeldWithIncrementedGeneration() {
        val state = PhonePairingState(generation = 3L)
        val step = reducePhonePairing(state, PhonePairingEvent.OpenMark)
        assertEquals(4L, step.state.generation)
        val session = step.state.session
        assertTrue(session is PhonePairingSession.Held)
        assertEquals("", session.stagedCanonical)
        assertEquals(4L, session.generation)
        assertFalse(session.mismatchFailure)
        assertNull(step.dial)

        // OpenMark during Held is no-op
        val stepHeld = reducePhonePairing(step.state, PhonePairingEvent.OpenMark)
        assertEquals(step.state, stepHeld.state)
    }

    @Test
    fun routePhonePairIntentHandlesNullAcceptedAndRejected() {
        val initial = PhonePairingState()

        val nullStep = routePhonePairIntent(initial, null)
        assertEquals(initial, nullStep.state)
        assertNull(nullStep.dial)

        val rejectedStep = routePhonePairIntent(initial, "invalid-link")
        val rejectedSession = rejectedStep.state.session
        assertTrue(rejectedSession is PhonePairingSession.Entry)
        assertTrue(rejectedSession.invalidSubmit)
        assertNull(rejectedStep.dial)

        val validUrl = "https://go.solstone.app/p#0R0J6HB7H6NWVVR1VTPVXVYAZTXBW0938NKRKAYDXW00"
        val acceptedStep = routePhonePairIntent(initial, validUrl)
        val acceptedSession = acceptedStep.state.session
        assertTrue(acceptedSession is PhonePairingSession.Entry)
        assertEquals(validUrl, acceptedSession.stagedCanonical)
        assertFalse(acceptedSession.invalidSubmit)
        assertNull(acceptedStep.dial)
    }
}
