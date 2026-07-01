// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.diagnostics.CueSnapshot
import app.solstone.core.diagnostics.PairingFact
import app.solstone.core.diagnostics.StatusCue
import app.solstone.core.diagnostics.cueFor
import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessControllerVisibleStartTest {
    @Test
    fun visibleStartIgnoresRehydrateOnlyBlockers() {
        val f = unpairedFixture(
            foregroundStartAllowed = FakeForegroundStartAllowed(false),
        )

        f.controller.ensureObserving()

        assertEquals(1, f.lifecycle.starts)
    }

    @Test
    fun visibleStartStillRequiresPermissions() {
        val f = fixture(
            permissionStatus = grantedPermissions().copy(cameraGranted = false),
            snapshot = stoppedSnapshot(),
        )

        f.controller.ensureObserving()

        assertEquals(0, f.lifecycle.starts)
    }

    @Test
    fun ensureObservingIsIdempotentAcrossTwoTriggers() {
        lateinit var f: Fixture
        f = fixture(
            sourceSnapshotProvider = {
                SourceRuntimeSnapshot(
                    engineRunning = f.lifecycle.starts > 0,
                    providerEmitting = true,
                    storageOk = true,
                    exemptionVerified = true,
                )
            },
        )

        f.controller.ensureObserving()
        f.controller.ensureObserving()

        assertEquals(1, f.lifecycle.starts)
    }

    @Test
    fun rehydrateAndVisibleStartModesDifferOnSameFixture() {
        val f = unpairedFixture(
            foregroundStartAllowed = FakeForegroundStartAllowed(false),
        )
        f.desiredStore.setDesiredOn(true)

        f.controller.reconcile(ObserverStartMode.Rehydrate)

        assertEquals(0, f.lifecycle.starts)

        f.controller.ensureObserving()

        assertEquals(1, f.lifecycle.starts)
    }

    @Test
    fun visibleStartWhileUnpairedStaysAudibleAsNotPaired() {
        val f = unpairedFixture(
            foregroundStartAllowed = FakeForegroundStartAllowed(false),
        )

        f.controller.ensureObserving()

        val diagnostics = f.controller.diagnostics()
        val current = CueSnapshot(
            state = diagnostics.state,
            reason = diagnostics.reason,
            pairing = f.controller.pairingFact(),
            lastFailureAt = f.controller.syncState().lastFailureAt,
        )
        val prev = current.copy(state = SourceState.OFF, pairing = PairingFact.PAIRED)

        assertEquals(SourceState.NEEDS_ATTENTION, current.state)
        assertEquals(StatusCue.NOT_PAIRED, cueFor(prev, current))
    }

    private fun unpairedFixture(
        foregroundStartAllowed: FakeForegroundStartAllowed = FakeForegroundStartAllowed(),
    ): Fixture =
        fixture(
            foregroundStartAllowed = foregroundStartAllowed,
            plStatusProbe = PlStatusProbe { HarnessPlStatus.NotPaired },
            endpointStore = FakeEndpointStore(null),
            credentialStore = FakeCredentialStore(null),
            identityStore = FakeIdentityStore(null),
            snapshot = stoppedSnapshot(),
        )

    private fun stoppedSnapshot(): SourceRuntimeSnapshot =
        SourceRuntimeSnapshot(
            engineRunning = false,
            providerEmitting = true,
            storageOk = true,
            exemptionVerified = true,
        )
}
