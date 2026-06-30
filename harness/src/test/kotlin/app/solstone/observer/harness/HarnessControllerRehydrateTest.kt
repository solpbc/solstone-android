// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.core.pl.DirectEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessControllerRehydrateTest {
    @Test
    fun reconcileSelfHealsWhenTransportBlockerClears() {
        var pl: HarnessPlStatus = HarnessPlStatus.PairedButUnreachable("down")
        val f = fixture(
            plStatusProbe = PlStatusProbe { pl },
            snapshot = SourceRuntimeSnapshot(
                engineRunning = false,
                providerEmitting = true,
                storageOk = true,
                exemptionVerified = true,
            ),
        )
        f.desiredStore.setDesiredOn(true)

        f.controller.reconcile()

        assertEquals(0, f.lifecycle.starts)

        // No-permanent-stranding regression: RED on current main, where only FGS-triggered
        // rehydrate exists, so a blocker that clears later never recovers.
        pl = HarnessPlStatus.Reachable(200)
        f.controller.reconcile()

        assertEquals(1, f.lifecycle.starts)
        assertTrue(f.controller.desiredOn)
    }

    @Test
    fun reconcileNoopsWhenNotDesired() {
        val f = fixture(plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) })

        f.controller.reconcile()

        assertEquals(0, f.lifecycle.starts)
    }

    @Test
    fun reconcilePipelineDiedAttemptsStart() {
        val f = fixture(
            plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) },
            snapshot = SourceRuntimeSnapshot(
                engineRunning = false,
                providerEmitting = true,
                storageOk = true,
                exemptionVerified = true,
            ),
        )
        f.desiredStore.setDesiredOn(true)

        f.controller.reconcile()

        assertEquals(1, f.lifecycle.starts)
    }

    @Test
    fun reconcileIsIdempotentAcrossTwoTriggers() {
        lateinit var f: Fixture
        f = fixture(
            plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) },
            sourceSnapshotProvider = {
                SourceRuntimeSnapshot(
                    engineRunning = f.lifecycle.starts > 0,
                    providerEmitting = true,
                    storageOk = true,
                    exemptionVerified = true,
                )
            },
        )
        f.desiredStore.setDesiredOn(true)

        f.controller.reconcile()
        f.controller.reconcile()

        assertEquals(1, f.lifecycle.starts)
    }

    @Test
    fun reconcileRegistersNetworkOnceAcrossRepeats() {
        val f = fixture(
            plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) },
            networkAvailability = FakeNetworkAvailability(),
            snapshot = SourceRuntimeSnapshot(
                engineRunning = false,
                providerEmitting = true,
                storageOk = true,
                exemptionVerified = true,
            ),
        )
        f.desiredStore.setDesiredOn(true)

        f.controller.reconcile()
        f.controller.reconcile()

        assertEquals(1, f.networkAvailability?.startCalls)
    }

    @Test
    fun startReadinessSurfacesCanonicalReasons() {
        val permission = fixture(
            permissionStatus = grantedPermissions().copy(cameraGranted = false),
            plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) },
        )
        permission.desiredStore.setDesiredOn(true)
        assertTrue(
            ReasonCode.PERMISSION_REVOKED in
                permission.controller.startReadiness(ObserverStartMode.Rehydrate).blockers,
        )

        val unpaired = fixture(
            endpointStore = FakeEndpointStore(null),
            credentialStore = FakeCredentialStore(null),
            identityStore = FakeIdentityStore(null),
            plStatusProbe = PlStatusProbe { HarnessPlStatus.NotPaired },
        )
        unpaired.desiredStore.setDesiredOn(true)
        assertTrue(ReasonCode.UNPAIRED in unpaired.controller.startReadiness(ObserverStartMode.Rehydrate).blockers)

        val transport = fixture(plStatusProbe = PlStatusProbe { HarnessPlStatus.PairedButUnreachable("down") })
        transport.desiredStore.setDesiredOn(true)
        assertTrue(
            ReasonCode.TRANSPORT_UNAVAILABLE in
                transport.controller.startReadiness(ObserverStartMode.Rehydrate).blockers,
        )

        val foreground = fixture(
            plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) },
            foregroundStartAllowed = FakeForegroundStartAllowed(false),
        )
        foreground.desiredStore.setDesiredOn(true)
        assertTrue(
            ReasonCode.FOREGROUND_START_NOT_ALLOWED in
                foreground.controller.startReadiness(ObserverStartMode.Rehydrate).blockers,
        )

        val notDesired = fixture(plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) })
        assertTrue(ReasonCode.NONE in notDesired.controller.startReadiness(ObserverStartMode.Rehydrate).blockers)
    }

    @Test
    fun desiredOnWithoutEngineRunningDiagnosticsNeedsAttention() {
        val f = fixture(
            snapshot = SourceRuntimeSnapshot(
                engineRunning = false,
                providerEmitting = true,
                storageOk = true,
                exemptionVerified = true,
            ),
            endpointStore = FakeEndpointStore(DirectEndpoint("10.0.0.2", 7657)),
            credentialStore = FakeCredentialStore(credential()),
            identityStore = FakeIdentityStore(pairedHome()),
        )
        f.desiredStore.setDesiredOn(true)

        val diagnostics = f.controller.diagnostics()

        assertEquals(SourceState.NEEDS_ATTENTION, diagnostics.state)
        assertEquals(ReasonCode.REBOOTED, diagnostics.reason)
    }
}
