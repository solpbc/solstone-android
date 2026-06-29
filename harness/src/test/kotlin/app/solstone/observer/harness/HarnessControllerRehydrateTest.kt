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
    fun rehydrateStartsWhenAllGatesPass() {
        val f = fixture(plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) })
        f.desiredStore.setDesiredOn(true)

        val readiness = f.controller.rehydrate()

        assertTrue(readiness.allowed)
        assertEquals(emptySet(), readiness.blockers)
        assertEquals(1, f.lifecycle.starts)
        assertTrue(f.controller.desiredOn)
    }

    @Test
    fun allowedRehydrateStartsOpportunisticSync() {
        val f = fixture(
            plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) },
            networkAvailability = FakeNetworkAvailability(),
        )
        f.desiredStore.setDesiredOn(true)

        val readiness = f.controller.rehydrate()

        assertTrue(readiness.allowed)
        assertEquals(1, f.lifecycle.starts)
        assertEquals(1, f.networkAvailability?.startCalls)
    }

    @Test
    fun rehydrateBlockedLeavesDesiredOnAndDoesNotStart() {
        val cases = listOf(
            blockedFixture(
                blocker = ObserverStartBlocker.PermissionsMissing,
                fixture = fixture(
                    permissionStatus = grantedPermissions().copy(cameraGranted = false),
                    plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) },
                ),
            ),
            blockedFixture(
                blocker = ObserverStartBlocker.Unpaired,
                fixture = fixture(
                    endpointStore = FakeEndpointStore(null),
                    credentialStore = FakeCredentialStore(null),
                    identityStore = FakeIdentityStore(null),
                    plStatusProbe = PlStatusProbe { HarnessPlStatus.NotPaired },
                ),
            ),
            blockedFixture(
                blocker = ObserverStartBlocker.TransportUnavailable,
                fixture = fixture(
                    plStatusProbe = PlStatusProbe { HarnessPlStatus.PairedButUnreachable("down") },
                ),
            ),
            blockedFixture(
                blocker = ObserverStartBlocker.ForegroundStartNotAllowed,
                fixture = fixture(
                    plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) },
                    foregroundStartAllowed = FakeForegroundStartAllowed(false),
                ),
            ),
        )

        cases.forEach { case ->
            case.fixture.desiredStore.setDesiredOn(true)
            val readiness = case.fixture.controller.rehydrate()

            assertFalse(readiness.allowed)
            assertTrue(case.blocker in readiness.blockers)
            assertEquals(0, case.fixture.lifecycle.starts)
            assertTrue(case.fixture.controller.desiredOn)
        }
    }

    @Test
    fun blockedRehydrateDoesNotStartOpportunisticSync() {
        val blocked = fixture(
            plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) },
            foregroundStartAllowed = FakeForegroundStartAllowed(false),
            networkAvailability = FakeNetworkAvailability(),
        )
        blocked.desiredStore.setDesiredOn(true)

        val blockedReadiness = blocked.controller.rehydrate()

        assertFalse(blockedReadiness.allowed)
        assertEquals(0, blocked.lifecycle.starts)
        assertEquals(0, blocked.networkAvailability?.startCalls)

        val notDesired = fixture(
            plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) },
            networkAvailability = FakeNetworkAvailability(),
        )

        val notDesiredReadiness = notDesired.controller.rehydrate()

        assertFalse(notDesiredReadiness.allowed)
        assertEquals(0, notDesired.lifecycle.starts)
        assertEquals(0, notDesired.networkAvailability?.startCalls)
    }

    @Test
    fun rehydrateNoopsWhenNotDesired() {
        val f = fixture(plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) })

        val readiness = f.controller.rehydrate()

        assertFalse(readiness.allowed)
        assertEquals(setOf(ObserverStartBlocker.NotDesired), readiness.blockers)
        assertEquals(0, f.lifecycle.starts)
    }

    @Test
    fun repeatedAllowedRehydrateRegistersNetworkOnce() {
        val f = fixture(
            plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) },
            networkAvailability = FakeNetworkAvailability(),
        )
        f.desiredStore.setDesiredOn(true)

        assertTrue(f.controller.rehydrate().allowed)
        assertTrue(f.controller.rehydrate().allowed)

        assertEquals(1, f.networkAvailability?.startCalls)
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

    private data class BlockedCase(
        val blocker: ObserverStartBlocker,
        val fixture: Fixture,
    )

    private fun blockedFixture(blocker: ObserverStartBlocker, fixture: Fixture): BlockedCase =
        BlockedCase(blocker, fixture)
}
