// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SilencedFact
import app.solstone.core.model.SourceState
import app.solstone.core.sources.SourceCondition
import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.fgs.ObserverForegroundService
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessControllerReconcileTypeMissingTest {

    @After
    fun tearDown() {
        ObserverForegroundService.heldCaptureForegroundTypes = null
    }

    @Test
    fun typeMissingReconcileWithVisibleOwnerRestartsCaptureForHeldTypesPreservingWishes() {
        ObserverForegroundService.heldCaptureForegroundTypes = setOf(CaptureForegroundType.MICROPHONE)
        val visibleAuth = FakeVisibleCaptureAuthority(present = true)
        val f = fixture(
            visibleCaptureAuthority = visibleAuth,
            snapshot = SourceRuntimeSnapshot(
                engineRunning = true,
                providerEmitting = true,
                storageOk = true,
                silenced = SilencedFact.NOT_SILENCED,
                engineStartIssued = true,
            ),
        )
        f.desiredStore.setDesiredOn(true)

        val runningCondition = SourceCondition(
            desiredOn = true,
            running = true,
            available = true,
            needsAttention = false,
            paused = false,
            silenced = SilencedFact.NOT_SILENCED,
        )

        val registry = sourceRegistry(
            f = f,
            registrations = listOf(
                SourceRegistration(
                    sourceId = "audio",
                    engine = FakeSourceEngine(conditionValue = runningCondition),
                    requiredPermissionsGranted = { it.microphoneGranted },
                    captureForegroundType = CaptureForegroundType.MICROPHONE,
                ),
                SourceRegistration(
                    sourceId = "location",
                    engine = FakeSourceEngine(conditionValue = runningCondition),
                    requiredPermissionsGranted = { it.locationGranted },
                    captureForegroundType = CaptureForegroundType.LOCATION,
                ),
            ),
        )
        registry.setWish("audio", SourceWish.On)
        registry.setWish("location", SourceWish.On)

        val initialSnapshot = registry.snapshot()
        val audioRow = initialSnapshot.sources.first { it.sourceId == "audio" }
        val locationRow = initialSnapshot.sources.first { it.sourceId == "location" }
        assertEquals(SourceState.ON, audioRow.state)
        assertEquals(SourceState.NEEDS_ATTENTION, locationRow.state)
        assertEquals(ReasonCode.FOREGROUND_TYPE_NOT_HELD, locationRow.reason)
        assertEquals(SourceState.ON, f.controller.diagnostics().state)

        f.controller.reconcile(ObserverStartMode.Rehydrate)
        assertEquals(0, f.lifecycle.restarts)
        f.controller.reconcile(ObserverStartMode.VisibleStart)
        assertEquals(1, f.lifecycle.restarts)

        // Wishes unchanged
        val afterSnapshot = registry.snapshot()
        assertEquals(SourceWish.On, afterSnapshot.sources.first { it.sourceId == "audio" }.wish)
        assertEquals(SourceWish.On, afterSnapshot.sources.first { it.sourceId == "location" }.wish)

        // Ensure observing also restarts
        f.controller.ensureObserving()
        assertEquals(2, f.lifecycle.restarts)

        // Without visible owner -> does not restart
        visibleAuth.present = false
        f.controller.reconcile(ObserverStartMode.VisibleStart)
        assertEquals(2, f.lifecycle.restarts)

        // With desiredOn = false -> does not restart
        visibleAuth.present = true
        f.desiredStore.setDesiredOn(false)
        f.controller.reconcile(ObserverStartMode.VisibleStart)
        assertEquals(2, f.lifecycle.restarts)
    }
}
