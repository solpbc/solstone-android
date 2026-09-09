// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SyncCredentialsTest {
    @Test
    fun optionalOpenUsesCurrentAccessWithoutSuppressingSamePairingMetadata() {
        val initial = identity(null, IdentityState.PAIRED, "https://old.example", "old-token")
        val mutator = app.solstone.platform.identity.file.FileIdentityMutator(FakeIdentityStore(initial))
        val direct = SyncTransport.Direct(endpoint())
        val oldRelay = SyncTransport.Relay("https://old.example", "home", "old-token")
        mutator.mutateIfCurrent(mutator.accessSnapshot()!!) {
            it.copy(relayOrigin = "https://new.example", deviceToken = "new-token")
        }
        assertEquals(direct, currentOptionalTransport(direct, initial, mutator))
        assertEquals(SyncTransport.Relay("https://new.example", "home", "new-token"), currentOptionalTransport(oldRelay, initial, mutator))
        mutator.clearRelayAccess(mutator.accessSnapshot()!!)
        assertEquals(direct, currentOptionalTransport(direct, initial, mutator))
        assertEquals(null, currentOptionalTransport(oldRelay, initial, mutator))
        mutator.installNewPairing(initial.copy(clientCertFingerprint = "sha256:other"))
        assertEquals(null, currentOptionalTransport(direct, initial, mutator))
    }

    @Test
    fun directAccessRemainsCurrentAcrossSamePairingRelayMutation() {
        val initial = identity(null, IdentityState.PAIRED, "https://old.example", "old-token")
        val mutator = app.solstone.platform.identity.file.FileIdentityMutator(FakeIdentityStore(initial))
        val selected = mutator.accessSnapshot()!!

        mutator.mutateIfCurrent(selected) {
            it.copy(relayOrigin = "https://new.example", deviceToken = "new-token")
        }

        assertEquals(true, transportAccessStillCurrent(SyncTransport.Direct(endpoint()), initial, selected, mutator))
    }

    @Test
    fun relayAccessIsObsoleteAfterSamePairingRelayMutation() {
        val initial = identity(null, IdentityState.PAIRED, "https://old.example", "old-token")
        val mutator = app.solstone.platform.identity.file.FileIdentityMutator(FakeIdentityStore(initial))
        val selected = mutator.accessSnapshot()!!

        mutator.mutateIfCurrent(selected) {
            it.copy(relayOrigin = "https://new.example", deviceToken = "new-token")
        }

        assertEquals(
            false,
            transportAccessStillCurrent(
                SyncTransport.Relay("https://old.example", "home", "old-token"),
                initial,
                selected,
                mutator,
            ),
        )
    }

    @Test
    fun directAccessIsObsoleteAfterPairingGenerationChanges() {
        val initial = identity(null, IdentityState.PAIRED, "https://old.example", "old-token")
        val mutator = app.solstone.platform.identity.file.FileIdentityMutator(FakeIdentityStore(initial))
        val selected = mutator.accessSnapshot()!!

        mutator.installNewPairing(initial.copy(clientCertFingerprint = "sha256:replacement"))

        assertEquals(false, transportAccessStillCurrent(SyncTransport.Direct(endpoint()), initial, selected, mutator))
    }

    @Test
    fun directAccessIsObsoleteAfterSamePairingRevocation() {
        val initial = identity(null, IdentityState.PAIRED, "https://old.example", "old-token")
        val mutator = app.solstone.platform.identity.file.FileIdentityMutator(FakeIdentityStore(initial))
        val selected = mutator.accessSnapshot()!!

        mutator.mutateIfCurrent(selected) { it.copy(state = IdentityState.REVOKED) }

        assertEquals(false, transportAccessStillCurrent(SyncTransport.Direct(endpoint()), initial, selected, mutator))
    }

    @Test
    fun readyWhenEndpointCredentialAndPairedIdentityWithHandleExist() {
        val result = recoverSyncCredentials(
            endpointStore = FakeEndpointStore(endpoint()),
            credentialStore = FakeCredentialStore(credential()),
            identityStore = FakeIdentityStore(identity(observerHandle = "obs_123", state = IdentityState.PAIRED)),
        )

        val ready = assertIs<SyncCredentials.Ready>(result)
        assertEquals(SyncTransport.Direct(endpoint()), ready.transport)
        assertEquals("obs_123", ready.identity.observerHandle)
    }

    @Test
    fun readyWhenPairedWithoutObserverHandle() {
        val result = recoverSyncCredentials(
            endpointStore = FakeEndpointStore(endpoint()),
            credentialStore = FakeCredentialStore(credential()),
            identityStore = FakeIdentityStore(identity(observerHandle = null, state = IdentityState.PAIRED)),
        )

        val ready = assertIs<SyncCredentials.Ready>(result)
        assertEquals(SyncTransport.Direct(endpoint()), ready.transport)
        assertEquals(null, ready.identity.observerHandle)
    }

    @Test
    fun readyForRelayWhenIdentityHasRelayOriginAndDeviceTokenAndNoDirectEndpoint() {
        val result = recoverSyncCredentials(
            endpointStore = FakeEndpointStore(null),
            credentialStore = FakeCredentialStore(credential()),
            identityStore = FakeIdentityStore(
                identity(
                    observerHandle = "obs_123",
                    state = IdentityState.PAIRED,
                    relayOrigin = "https://link.solstone.app",
                    deviceToken = "device-token",
                ),
            ),
        )

        val ready = assertIs<SyncCredentials.Ready>(result)
        assertEquals(SyncTransport.Relay("https://link.solstone.app", "home", "device-token"), ready.transport)
        assertEquals("obs_123", ready.identity.observerHandle)
    }

    @Test
    fun directEndpointWinsEvenWhenRelayConfigured() {
        val result = recoverSyncCredentials(
            endpointStore = FakeEndpointStore(endpoint()),
            credentialStore = FakeCredentialStore(credential()),
            identityStore = FakeIdentityStore(
                identity(
                    observerHandle = "obs_123",
                    state = IdentityState.PAIRED,
                    relayOrigin = "https://link.solstone.app",
                    deviceToken = "device-token",
                ),
            ),
        )

        val ready = assertIs<SyncCredentials.Ready>(result)
        assertEquals(SyncTransport.Direct(endpoint()), ready.transport)
    }

    @Test
    fun relayIneligibleFallsBackToMissingEndpointWhenNoDirectEndpoint() {
        val result = recoverSyncCredentials(
            endpointStore = FakeEndpointStore(null),
            credentialStore = FakeCredentialStore(credential()),
            identityStore = FakeIdentityStore(
                identity(
                    observerHandle = "obs_123",
                    state = IdentityState.PAIRED,
                    relayOrigin = "https://link.solstone.app",
                    deviceToken = "device-token",
                ),
            ),
            relayLiveEligible = false,
        )

        val repair = assertIs<SyncCredentials.NeedsRepair>(result)
        assertEquals("missing endpoint and relay credentials", repair.reason)
    }

    @Test
    fun directWinsWithoutDeviceToken() {
        val result = recoverSyncCredentials(
            endpointStore = FakeEndpointStore(endpoint()),
            credentialStore = FakeCredentialStore(credential()),
            identityStore = FakeIdentityStore(
                identity(
                    observerHandle = "obs_123",
                    state = IdentityState.PAIRED,
                    relayOrigin = "https://link.solstone.app",
                    deviceToken = null,
                ),
            ),
        )

        val ready = assertIs<SyncCredentials.Ready>(result)
        assertEquals(SyncTransport.Direct(endpoint()), ready.transport)
    }

    @Test
    fun needsRepairNamesMissingFacts() {
        assertRepair(
            "missing endpoint and relay credentials",
            endpoint = null,
            credential = credential(),
            identity = identity(observerHandle = "obs_123", state = IdentityState.PAIRED),
        )
        assertRepair(
            "missing credential",
            endpoint = endpoint(),
            credential = null,
            identity = identity(observerHandle = "obs_123", state = IdentityState.PAIRED),
        )
        assertRepair(
            "missing identity",
            endpoint = endpoint(),
            credential = credential(),
            identity = null,
        )
        assertRepair(
            "identity not paired",
            endpoint = endpoint(),
            credential = credential(),
            identity = identity(observerHandle = "obs_123", state = IdentityState.REVOKED),
        )
        assertRepair(
            "missing endpoint and relay credentials",
            endpoint = null,
            credential = credential(),
            identity = identity(
                observerHandle = "obs_123",
                state = IdentityState.PAIRED,
                relayOrigin = "https://link.solstone.app",
                deviceToken = null,
            ),
        )
    }

    @Test
    fun relayFallbackTransportReturnsRelayWhenLiveEligibleAndOriginAndTokenPresent() {
        val id = identity(
            observerHandle = "obs_123",
            state = IdentityState.PAIRED,
            relayOrigin = "https://link.solstone.app",
            deviceToken = "token_abc",
        )
        val transport = relayFallbackTransport(id, relayLiveEligible = true)
        assertEquals(SyncTransport.Relay("https://link.solstone.app", "home", "token_abc"), transport)

        assertEquals(null, relayFallbackTransport(id, relayLiveEligible = false))
        assertEquals(null, relayFallbackTransport(id.copy(deviceToken = null), relayLiveEligible = true))
        assertEquals(null, relayFallbackTransport(id.copy(relayOrigin = null), relayLiveEligible = true))
    }

    private fun assertRepair(
        reason: String,
        endpoint: DirectEndpoint?,
        credential: ClientCredential?,
        identity: PairedHome?,
    ) {
        val result = recoverSyncCredentials(
            endpointStore = FakeEndpointStore(endpoint),
            credentialStore = FakeCredentialStore(credential),
            identityStore = FakeIdentityStore(identity),
        )
        assertEquals(reason, assertIs<SyncCredentials.NeedsRepair>(result).reason)
    }

    private fun endpoint(): DirectEndpoint = DirectEndpoint("192.0.2.10", 7657)

    private fun credential(): ClientCredential =
        ClientCredential("private", "client", listOf("ca"))

    private fun identity(
        observerHandle: String?,
        state: IdentityState,
        relayOrigin: String? = null,
        deviceToken: String? = null,
    ): PairedHome =
        PairedHome(
            instanceId = "home",
            homeLabel = "Home",
            relayOrigin = relayOrigin,
            caChainFingerprint = "sha256:ca",
            clientCertFingerprint = "sha256:client",
            observerHandle = observerHandle,
            deviceToken = deviceToken,
            expiresAt = null,
            state = state,
        )

    private class FakeEndpointStore(private var endpoint: DirectEndpoint?) : EndpointStore {
        override fun save(endpoint: DirectEndpoint) {
            this.endpoint = endpoint
        }

        override fun load(): DirectEndpoint? = endpoint

        override fun clear() {
            endpoint = null
        }
    }

    private class FakeCredentialStore(private var credential: ClientCredential?) : ClientCredentialStore {
        override fun save(credential: ClientCredential) {
            this.credential = credential
        }

        override fun load(): ClientCredential? = credential

        override fun clear() {
            credential = null
        }
    }

    private class FakeIdentityStore(private var identity: PairedHome?) : IdentityStore {
        override fun save(home: PairedHome) {
            identity = home
        }

        override fun load(): PairedHome? = identity

        override fun clear() {
            identity = null
        }
    }
}
