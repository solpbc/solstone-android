// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.diagnostics.PairingFact
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
    fun successfulStartStartsOpportunisticSync() {
        val f = fixture(networkAvailability = FakeNetworkAvailability())

        assertTrue(f.controller.start())

        assertEquals(1, f.networkAvailability?.startCalls)
    }

    @Test
    fun refusedStartDoesNotStartOpportunisticSync() {
        val f = fixture(
            permissionStatus = grantedPermissions().copy(cameraGranted = false),
            networkAvailability = FakeNetworkAvailability(),
        )

        assertFalse(f.controller.start())

        assertEquals(0, f.networkAvailability?.startCalls)
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
    fun relayPairedIdentityWithoutDirectEndpointIsPaired() {
        val f = fixture(
            endpointStore = FakeEndpointStore(null),
            credentialStore = FakeCredentialStore(credential()),
            identityStore = FakeIdentityStore(pairedHome(relayOrigin = "https://link.solstone.app")),
        )

        assertEquals(PairingFact.PAIRED, f.controller.pairingFact())
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
    fun scannedPairLinkSuccessFlushesPendingAfterPairLock() {
        val evidence = FakeEvidenceReader(sync = HarnessSyncState(2, null, null))
        val f = fixture(
            evidenceReader = evidence,
            networkAvailability = FakeNetworkAvailability(),
        )

        assertTrue(f.controller.onScannedPairLink(validPairLink()) != null)

        assertEquals(1, f.sync.calls)
        assertEquals(listOf("acquire", "release"), f.cameraLock.events)
    }

    @Test
    fun pairSuccessWithNoPendingEvidenceEnqueuesNothing() {
        val evidence = FakeEvidenceReader(sync = HarnessSyncState(0, null, null))
        val f = fixture(
            evidenceReader = evidence,
            networkAvailability = FakeNetworkAvailability(),
        )

        assertTrue(f.controller.onScannedPairLink(validPairLink()) != null)

        assertEquals(0, f.sync.calls)
    }

    @Test
    fun scannedPairLinkNonSuccessDoesNotFlushPending() {
        val evidence = FakeEvidenceReader(sync = HarnessSyncState(2, null, null))
        val f = fixture(
            pairProbe = PairProbe { _, _ ->
                HarnessPairProbeResult(true, 200, 503, "nope", "home", "10.0.0.2", 7657)
            },
            evidenceReader = evidence,
            networkAvailability = FakeNetworkAvailability(),
        )

        assertTrue(f.controller.onScannedPairLink(validPairLink()) != null)

        assertEquals(0, f.sync.calls)
    }

    @Test
    fun stopFlushesOpportunisticSyncBeforeStoppingLifecycle() {
        val evidence = FakeEvidenceReader(sync = HarnessSyncState(2, null, null))
        val f = fixture(
            evidenceReader = evidence,
            networkAvailability = FakeNetworkAvailability(),
        )

        f.controller.start()
        f.controller.stop()

        assertEquals(1, f.sync.calls)
        assertEquals(1, f.networkAvailability?.stopCalls)
        assertEquals(1, f.lifecycle.stops)
        assertFalse(f.controller.desiredOn)
    }

    @Test
    fun ownerStopWithNoPendingEvidenceEnqueuesNothing() {
        val evidence = FakeEvidenceReader(sync = HarnessSyncState(0, null, null))
        val f = fixture(
            evidenceReader = evidence,
            networkAvailability = FakeNetworkAvailability(),
        )

        f.controller.start()
        f.controller.stop()

        assertEquals(0, f.sync.calls)
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
        assertEquals(PairConnectionMode.PAIRING, result?.connectionMode)
        assertEquals(listOf("acquire", "release"), f.cameraLock.events)
    }

    @Test
    fun scannedRelayPairLinkAlreadyConnectedShortCircuitsProbe() {
        var relayCalls = 0
        val prior = pairedHome(
            instanceId = RELAY_INSTANCE_ID,
            relayOrigin = "https://link.solstone.app",
        )
        val identity = FakeIdentityStore(prior)
        val f = fixture(
            identityStore = identity,
            relayPairProbe = RelayPairProbe { _, _ ->
                relayCalls += 1
                error("relay probe should not run")
            },
        )

        val result = f.controller.onScannedPairLink(validRelayPairLink())

        assertEquals(0, relayCalls)
        assertEquals(PairConnectionMode.ALREADY_CONNECTED, result?.connectionMode)
        assertEquals(false, result?.handshakePinned)
        assertEquals("home", result?.homeLabel)
        assertEquals("link.solstone.app", result?.endpointHost)
        assertEquals(prior, identity.load())
    }

    @Test
    fun scannedRelayPairLinkSameNonPairedInstanceRunsReconnectMode() {
        var relayCalls = 0
        val f = fixture(
            identityStore = FakeIdentityStore(pairedHome(state = IdentityState.REVOKED, instanceId = RELAY_INSTANCE_ID)),
            relayPairProbe = RelayPairProbe { _, _ ->
                relayCalls += 1
                HarnessPairProbeResult(true, 200, 200, "", "home", "link.solstone.app", 443)
            },
        )

        val result = f.controller.onScannedPairLink(validRelayPairLink())

        assertEquals(1, relayCalls)
        assertEquals(PairConnectionMode.RECONNECTING, result?.connectionMode)
    }

    @Test
    fun scannedRelayPairLinkDifferentInstanceRunsPairingMode() {
        var relayCalls = 0
        val f = fixture(
            identityStore = FakeIdentityStore(pairedHome(instanceId = "different")),
            relayPairProbe = RelayPairProbe { _, _ ->
                relayCalls += 1
                HarnessPairProbeResult(true, 200, 200, "", "home", "link.solstone.app", 443)
            },
        )

        val result = f.controller.onScannedPairLink(validRelayPairLink())

        assertEquals(1, relayCalls)
        assertEquals(PairConnectionMode.PAIRING, result?.connectionMode)
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

    private companion object {
        const val RELAY_INSTANCE_ID = "12345678-1234-5678-1234-567812345678"
    }
}
