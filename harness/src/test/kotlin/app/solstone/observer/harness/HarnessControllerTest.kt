// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.IdentityState
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.core.pl.DirectEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class HarnessControllerTest {
    @Test
    fun startRefusedUntilRequiredPermissionsAreGranted() {
        val cameraDenied = fixture(permissionStatus = grantedPermissions().copy(cameraGranted = false))
        assertFalse(cameraDenied.controller.start())
        assertEquals(0, cameraDenied.lifecycle.starts)
        assertNotEquals(SourceState.ON, cameraDenied.controller.diagnostics().state)

        val locationDenied = fixture(
            permissionStatus = grantedPermissions().copy(fineLocationGranted = false, coarseLocationGranted = false),
        )
        assertFalse(locationDenied.controller.start())
        assertEquals(0, locationDenied.lifecycle.starts)
        assertNotEquals(SourceState.ON, locationDenied.controller.diagnostics().state)

        val granted = fixture()
        assertTrue(granted.controller.start())
        assertEquals(1, granted.lifecycle.starts)
    }

    @Test
    fun backgroundLocationDoesNotGateStart() {
        val f = fixture(permissionStatus = grantedPermissions().copy(backgroundLocationGranted = false))
        assertTrue(f.controller.start())
        assertEquals(1, f.lifecycle.starts)
    }

    @Test
    fun neverPairedDeviceTurnedOnMapsUnpaired() {
        val f = fixture(
            endpointStore = FakeEndpointStore(),
            credentialStore = FakeCredentialStore(),
            identityStore = FakeIdentityStore(),
        )
        assertTrue(f.controller.start())
        val diagnostics = f.controller.diagnostics()
        assertEquals(SourceState.NEEDS_ATTENTION, diagnostics.state)
        assertEquals(ReasonCode.UNPAIRED, diagnostics.reason)
    }

    @Test
    fun syncNowEnqueuesExactlyOnce() {
        val f = fixture()
        f.controller.syncNow()
        assertEquals(1, f.sync.calls)
    }

    @Test
    fun schedulePeriodicSyncEnqueuesExactlyOnce() {
        val f = fixture()
        f.controller.schedulePeriodicSync()
        assertEquals(1, f.sync.enqueuePeriodicCalls)
    }

    @Test
    fun invalidPairLinkDoesNothing() {
        val f = fixture()
        assertNull(f.controller.onScannedPairLink("nope"))
        assertTrue(f.cameraLock.events.isEmpty())
    }

    @Test
    fun scanReleasesLockWhenPairProbeFails() {
        val f = fixture(pairProbe = PairProbe { _, _ -> error("pair failed") })
        try {
            f.controller.onScannedPairLink(validPairLink())
            fail("pair failure should propagate")
        } catch (_: IllegalStateException) {
        }
        assertEquals(listOf("acquire", "release"), f.cameraLock.events)
        assertFalse(f.cameraLock.held)
    }

    @Test
    fun scannedRelayPairLinkDispatchesRelayProbe() {
        var directCalls = 0
        var relayCalls = 0
        val f = fixture(
            pairProbe = PairProbe { _, _ ->
                directCalls += 1
                error("direct should not run")
            },
            relayPairProbe = RelayPairProbe { link, _ ->
                relayCalls += 1
                assertEquals("12345678-1234-5678-1234-567812345678", link.instanceId)
                HarnessPairProbeResult(true, 200, 200, "", "home", "link.solstone.app", 443)
            },
        )

        val result = f.controller.onScannedPairLink(validRelayPairLink())

        assertEquals(0, directCalls)
        assertEquals(1, relayCalls)
        assertEquals(443, result?.endpointPort)
        assertEquals(listOf("acquire", "release"), f.cameraLock.events)
    }

    @Test
    fun scanReleasesLockWhenRelayPairProbeFails() {
        val f = fixture(relayPairProbe = RelayPairProbe { _, _ -> error("relay pair failed") })
        try {
            f.controller.onScannedPairLink(validRelayPairLink())
            fail("relay pair failure should propagate")
        } catch (_: IllegalStateException) {
        }
        assertEquals(listOf("acquire", "release"), f.cameraLock.events)
        assertFalse(f.cameraLock.held)
    }

    @Test
    fun revokedAfterPairingMapsAuthRevokedAndPairedButUnreachable() {
        val endpoint = FakeEndpointStore(DirectEndpoint("10.0.0.2", 7657))
        val credentials = FakeCredentialStore(credential())
        val identity = FakeIdentityStore(pairedHome(IdentityState.REVOKED))
        val f = fixture(
            endpointStore = endpoint,
            credentialStore = credentials,
            identityStore = identity,
            plStatusProbe = RealPlStatusProbe(endpoint, credentials, identity),
        )

        val diagnostics = f.controller.diagnostics()
        assertEquals(SourceState.NEEDS_ATTENTION, diagnostics.state)
        assertEquals(ReasonCode.AUTH_REVOKED, diagnostics.reason)
        assertIs<HarnessPlStatus.PairedButUnreachable>(f.controller.probePlStatus())
    }

    private fun validRelayPairLink(): String =
        "https://go.solstone.app/p#0C938NKR28T5CY0J6HB7G4HMASW03RJ004HMASW9NF6YY0938NKRKAYDXW0XXBDYXZ5FXENY04HMASW9NF6YY00"
}
