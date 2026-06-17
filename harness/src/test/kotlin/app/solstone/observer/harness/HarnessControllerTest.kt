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
    fun syncNowEnqueuesExactlyOnce() {
        val f = fixture()
        f.controller.syncNow()
        assertEquals(1, f.sync.calls)
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
}
