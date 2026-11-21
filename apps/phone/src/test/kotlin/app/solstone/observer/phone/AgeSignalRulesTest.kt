// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgeSignalRulesTest {
    @Test
    fun bandsReduceToTheOneCategoryTheRuleNeeds() {
        assertEquals(AgeCategory.UNDER_13, ageCategoryOf(0, 12))
        assertEquals(AgeCategory.TEEN, ageCategoryOf(13, 15))
        assertEquals(AgeCategory.TEEN, ageCategoryOf(16, 17))
        assertEquals(AgeCategory.ADULT_OR_UNKNOWN, ageCategoryOf(18, null))
        assertEquals(AgeCategory.ADULT_OR_UNKNOWN, ageCategoryOf(null, null))
        // A band that reaches below 13 cannot establish 13 and over.
        assertEquals(AgeCategory.UNDER_13, ageCategoryOf(0, 14))
        assertEquals(AgeCategory.UNDER_13, ageCategoryOf(11, 12))
        // An open band that starts below 18 may hold a teen.
        assertEquals(AgeCategory.TEEN, ageCategoryOf(15, null))
    }

    @Test
    fun eachAnswerLeadsToItsOutcome() {
        assertEquals(AgeGateOutcome.RUN, ageGateOutcome(null))
        assertEquals(AgeGateOutcome.RUN, ageGateOutcome(AgeSignalAnswer.NotRequired))
        assertEquals(AgeGateOutcome.RUN, ageGateOutcome(AgeSignalAnswer.NotShared))
        assertEquals(AgeGateOutcome.VERIFY, ageGateOutcome(AgeSignalAnswer.VerificationRequired))
        assertEquals(AgeGateOutcome.RUN, ageGateOutcome(shared(AgeCategory.ADULT_OR_UNKNOWN)))
        assertEquals(AgeGateOutcome.RUN, ageGateOutcome(shared(AgeCategory.TEEN)))
        assertEquals(AgeGateOutcome.HOLD, ageGateOutcome(shared(AgeCategory.TEEN, outstanding = true)))
        assertEquals(AgeGateOutcome.STOP, ageGateOutcome(shared(AgeCategory.UNDER_13)))
        assertEquals(AgeGateOutcome.STOP, ageGateOutcome(shared(AgeCategory.UNDER_13, outstanding = true)))
        // A significant change only holds a teen.
        assertEquals(AgeGateOutcome.RUN, ageGateOutcome(shared(AgeCategory.ADULT_OR_UNKNOWN, outstanding = true)))
        assertFalse(AgeGateOutcome.RUN.closesApp)
        assertTrue(listOf(AgeGateOutcome.HOLD, AgeGateOutcome.STOP, AgeGateOutcome.VERIFY).all { it.closesApp })
    }

    @Test
    fun whereTheLawDoesNotRequireItNothingPastTheFirstStepIsAsked() {
        val store = ScriptedStore(mandatory = listOf(false))
        assertEquals(AgeSignalAnswer.NotRequired, askAgeSignalStore(store))
        assertEquals(listOf("features"), store.calls)
    }

    @Test
    fun theAccessStatusDecidesWhetherTheBandIsRead() {
        val notShared = ScriptedStore(access = AgeSignalAccess.NOT_SHARED)
        assertEquals(AgeSignalAnswer.NotShared, askAgeSignalStore(notShared))
        assertEquals(listOf("features", "access"), notShared.calls)

        val unspecified = ScriptedStore(access = AgeSignalAccess.UNSPECIFIED)
        assertEquals(AgeSignalAnswer.NotShared, askAgeSignalStore(unspecified))

        val verify = ScriptedStore(access = AgeSignalAccess.VERIFICATION_REQUIRED)
        assertEquals(AgeSignalAnswer.VerificationRequired, askAgeSignalStore(verify))
        assertEquals(listOf("features", "access"), verify.calls)

        val shared = ScriptedStore(access = AgeSignalAccess.SHARED, signal = shared(AgeCategory.UNDER_13))
        assertEquals(shared(AgeCategory.UNDER_13), askAgeSignalStore(shared))
        assertEquals(listOf("features", "access", "signal"), shared.calls)
    }

    @Test
    fun aTransientFailureIsRetriedABoundedNumberOfTimesThenTheAppCarriesOn() {
        val failures = List(10) { AgeSignalFailure(transient = true) }
        val store = ScriptedStore(featureFailures = failures.toMutableList())
        val slept = mutableListOf<Long>()
        assertNull(askAgeSignalWithRetry(store, slept::add))
        assertEquals(AGE_SIGNAL_RETRY_DELAYS_MS, slept)
        assertEquals(1 + AGE_SIGNAL_RETRY_DELAYS_MS.size, store.calls.count { it == "features" })
        assertEquals(AgeGateOutcome.RUN, ageGateOutcome(askAgeSignalWithRetry(ScriptedStore(featureFailures = failures.toMutableList()), {})))
    }

    @Test
    fun aRetryThatSucceedsKeepsItsAnswer() {
        val store = ScriptedStore(
            featureFailures = mutableListOf(AgeSignalFailure(transient = true), AgeSignalFailure(transient = true)),
            access = AgeSignalAccess.SHARED,
            signal = shared(AgeCategory.TEEN, outstanding = true),
        )
        val slept = mutableListOf<Long>()
        assertEquals(shared(AgeCategory.TEEN, outstanding = true), askAgeSignalWithRetry(store, slept::add))
        assertEquals(listOf(250L, 500L), slept)
    }

    @Test
    fun aFailureThatCannotChangeIsNotRetried() {
        val store = ScriptedStore(featureFailures = mutableListOf(AgeSignalFailure(transient = false)))
        val slept = mutableListOf<Long>()
        assertNull(askAgeSignalWithRetry(store, slept::add))
        assertTrue(slept.isEmpty())
        assertEquals(listOf("features"), store.calls)
    }

    @Test
    fun storeErrorsThatWillNotChangeOnRetryAreNotTransient() {
        listOf(
            AgeSignalsErrorCode.API_NOT_AVAILABLE,
            AgeSignalsErrorCode.PLAY_STORE_NOT_FOUND,
            AgeSignalsErrorCode.PLAY_SERVICES_NOT_FOUND,
            AgeSignalsErrorCode.PLAY_STORE_VERSION_OUTDATED,
            AgeSignalsErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
            AgeSignalsErrorCode.APP_NOT_OWNED,
            AgeSignalsErrorCode.SDK_VERSION_OUTDATED,
            AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED,
        ).forEach { assertFalse(ageSignalErrorIsTransient(it), "code $it") }
        listOf(
            AgeSignalsErrorCode.NETWORK_ERROR,
            AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE,
            AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR,
            AgeSignalsErrorCode.INTERNAL_ERROR,
            -12345,
        ).forEach { assertTrue(ageSignalErrorIsTransient(it), "code $it") }
    }

    @Test
    fun aClosingOutcomeIsDurableBeforeRunningWorkIsStopped() {
        listOf(AgeGateOutcome.STOP, AgeGateOutcome.HOLD, AgeGateOutcome.VERIFY).forEach { outcome ->
            val events = mutableListOf<String>()
            val marker = RecordingMarker(events)
            assertTrue(applyAgeGateOutcome(outcome, marker, workAlreadyRunning = { true }, stopRunningWork = { events += "stop" }))
            assertEquals(listOf("write=true", "stop"), events, outcome.name)
            assertTrue(marker.closed())
        }
    }

    @Test
    fun aClosingOutcomeWithNothingRunningStopsNothing() {
        val events = mutableListOf<String>()
        applyAgeGateOutcome(AgeGateOutcome.STOP, RecordingMarker(events), workAlreadyRunning = { false }, stopRunningWork = { events += "stop" })
        assertEquals(listOf("write=true"), events)
    }

    @Test
    fun theNextLaunchThatRunsOpensTheGateAgain() {
        val events = mutableListOf<String>()
        val marker = RecordingMarker(events, closed = true)
        assertTrue(ageGateClosed(enabled = true, marker = marker))
        applyAgeGateOutcome(AgeGateOutcome.RUN, marker, workAlreadyRunning = { true }, stopRunningWork = { events += "stop" })
        assertEquals(listOf("write=false"), events)
        assertFalse(ageGateClosed(enabled = true, marker = marker))
    }

    @Test
    fun theDerivedOutcomeIsWhatAFreshProcessReads() {
        // Background work in a fresh process asks only the persisted fact, never the store.
        val persisted = RecordingMarker(mutableListOf())
        applyAgeGateOutcome(AgeGateOutcome.STOP, persisted, { false }, {})
        val freshProcess = RecordingMarker(mutableListOf(), closed = persisted.closed())
        assertTrue(ageGateClosed(enabled = true, marker = freshProcess))
        assertFalse(ageGateClosed(enabled = true, marker = null))
    }

    @Test
    fun offTheGateIsNeverConsulted() {
        val marker = object : AgeGateMarker {
            var reads = 0
            override fun closed(): Boolean { reads += 1; return true }
            override fun write(closed: Boolean): Boolean = error("off never writes")
        }
        assertFalse(ageGateClosed(enabled = false, marker = marker))
        assertEquals(0, marker.reads)
    }

    private fun shared(category: AgeCategory, outstanding: Boolean = false) =
        AgeSignalAnswer.Shared(category, parentApprovalOutstanding = outstanding)

    private class ScriptedStore(
        private val mandatory: List<Boolean> = listOf(true),
        private val featureFailures: MutableList<AgeSignalFailure> = mutableListOf(),
        private val access: AgeSignalAccess = AgeSignalAccess.NOT_SHARED,
        private val signal: AgeSignalAnswer.Shared? = null,
    ) : AgeSignalStore {
        val calls = mutableListOf<String>()

        override fun mandatorySharingApplies(): Boolean {
            calls += "features"
            if (featureFailures.isNotEmpty()) throw featureFailures.removeAt(0)
            return mandatory.first()
        }

        override fun access(): AgeSignalAccess {
            calls += "access"
            return access
        }

        override fun sharedSignal(): AgeSignalAnswer.Shared {
            calls += "signal"
            return requireNotNull(signal)
        }
    }

    private class RecordingMarker(private val events: MutableList<String>, private var closed: Boolean = false) : AgeGateMarker {
        override fun closed(): Boolean = closed
        override fun write(closed: Boolean): Boolean {
            events += "write=$closed"
            this.closed = closed
            return true
        }
    }
}
