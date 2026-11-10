// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.solstone.observer.formfactor.phone.PhonePairingEvent
import app.solstone.observer.formfactor.phone.PhonePairingSession
import app.solstone.observer.formfactor.phone.PhonePairingState
import app.solstone.observer.formfactor.phone.PhonePairingStep
import app.solstone.observer.formfactor.phone.reducePhonePairing
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.scaffold.CameraAsk
import app.solstone.platform.work.SyncStores
import app.solstone.core.identity.PairingGraphSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.solstone.observer.formfactor.phone.PairingMismatchResult

class PhonePairingViewModel : ViewModel() {
    var pairingState by mutableStateOf(PhonePairingState())
        private set

    var cameraAsk by mutableStateOf(CameraAsk.NotAsked)
    var inFlightPairAttempts by mutableStateOf(0)
        private set

    /** UI cancellation does not cancel an owner-approved dial or its durable result. */
    internal fun launchPairAttempt(operation: suspend () -> Unit): Job {
        inFlightPairAttempts += 1
        return viewModelScope.launch {
            try { operation() } finally { inFlightPairAttempts -= 1 }
        }
    }

    internal enum class ActionKind { MISMATCH, CONFIRM }
    private data class OwnerAction(val kind: ActionKind, val task: Deferred<Boolean>)
    private var ownerAction by mutableStateOf<OwnerAction?>(null)
    val mismatchInProgress: Boolean get() = ownerAction?.kind == ActionKind.MISMATCH

    suspend fun mismatch(
        expected: app.solstone.core.identity.PairingGeneration,
        controller: HarnessController,
        stores: SyncStores,
    ): PairingMismatchResult {
        var result = PairingMismatchResult.LocalFailure
        runOwnerAction(ActionKind.MISMATCH, expected, controller, stores) {
            result = performPhoneMismatch(stores, expected)
            result != PairingMismatchResult.LocalFailure
        }
        return result
    }

    suspend fun confirm(
        context: android.content.Context,
        expected: app.solstone.core.identity.PairingGeneration,
        controller: HarnessController,
        stores: SyncStores,
    ): Boolean = runOwnerAction(ActionKind.CONFIRM, expected, controller, stores) {
        withContext(Dispatchers.IO) { performPhoneConfirm(context, stores, expected.clientCertFingerprint) }
    }

    internal suspend fun runOwnerAction(
        kind: ActionKind,
        expected: app.solstone.core.identity.PairingGeneration,
        controller: HarnessController,
        stores: SyncStores,
        operation: suspend () -> Boolean,
    ): Boolean = withContext(Dispatchers.Main) {
        // Admission is on Main for both actions, including callbacks begun on IO.
        if (ownerAction != null) return@withContext false
        val held = pairingState.session as? PhonePairingSession.Held ?: return@withContext false
        if (currentPairing(stores) != expected) return@withContext false
        val task = viewModelScope.async {
            val succeeded = operation()
            stores.publisher.withMutationBoundary {
                val currentHeld = pairingState.session as? PhonePairingSession.Held
                val snapshot = stores.publisher.currentSnapshot()
                val current = (snapshot as? PairingGraphSnapshot.Committed)?.pairing
                if (succeeded && currentHeld != null) {
                    when (kind) {
                        ActionKind.MISMATCH ->
                            performDispatch(PhonePairingEvent.MismatchFinished(currentHeld.generation, true), controller, stores)
                        ActionKind.CONFIRM -> if (current == expected) {
                            performDispatch(PhonePairingEvent.Confirm, controller, stores)
                        }
                    }
                } else if (kind == ActionKind.MISMATCH) {
                    performDispatch(PhonePairingEvent.MismatchFinished(held.generation, false), controller, stores)
                }
            }
            succeeded
        }
        ownerAction = OwnerAction(kind, task)
        task.invokeOnCompletion {
            viewModelScope.launch(Dispatchers.Main) {
                if (ownerAction?.task === task) ownerAction = null
            }
        }
        task.await()
    }

    /**
     * The pairing the open question was about is gone or refused: there is nothing left to answer.
     * An owner's own no has its own ending, so it is left to finish.
     */
    fun closeQuestionIfNothingToAnswer(
        awaiting: Boolean,
        controller: HarnessController,
        stores: SyncStores,
    ) {
        val held = pairingState.session as? PhonePairingSession.Held ?: return
        if (awaiting || held.disconnected || mismatchInProgress) return
        performDispatch(PhonePairingEvent.Dismiss, controller, stores)
    }

    fun dispatch(
        event: PhonePairingEvent,
        controller: HarnessController,
        stores: SyncStores,
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            performDispatch(event, controller, stores)
        } else {
            viewModelScope.launch(Dispatchers.Main) {
                performDispatch(event, controller, stores)
            }
        }
    }

    private fun performDispatch(
        event: PhonePairingEvent,
        controller: HarnessController,
        stores: SyncStores,
    ) {
        if (event is PhonePairingEvent.OpenScan && pairingState.session !is PhonePairingSession.Scanning) {
            cameraAsk = CameraAsk.NotAsked
        }
        val step = reducePhonePairing(pairingState, event)
        applyStep(step, controller, stores)
    }

    fun applyStep(
        step: PhonePairingStep,
        controller: HarnessController,
        stores: SyncStores,
    ) {
        pairingState = step.state

        val dial = step.dial
        if (dial != null) {
            val generation = step.state.generation
            launchPairAttempt {
                val result = withContext(Dispatchers.IO) { controller.dispatchPairLink(dial) }
                dispatch(PhonePairingEvent.AttemptFinished(generation, result), controller, stores)
            }
        }
    }
}
