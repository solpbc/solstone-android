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
    fun readyWhenEndpointCredentialAndPairedIdentityWithHandleExist() {
        val result = recoverSyncCredentials(
            endpointStore = FakeEndpointStore(endpoint()),
            credentialStore = FakeCredentialStore(credential()),
            identityStore = FakeIdentityStore(identity(observerHandle = "obs_123", state = IdentityState.PAIRED)),
        )

        val ready = assertIs<SyncCredentials.Ready>(result)
        assertEquals(endpoint(), ready.endpoint)
        assertEquals("obs_123", ready.handle)
    }

    @Test
    fun needsRepairNamesMissingFacts() {
        assertRepair(
            "missing endpoint",
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
            "missing observer handle",
            endpoint = endpoint(),
            credential = credential(),
            identity = identity(observerHandle = null, state = IdentityState.PAIRED),
        )
        assertRepair(
            "identity not paired",
            endpoint = endpoint(),
            credential = credential(),
            identity = identity(observerHandle = "obs_123", state = IdentityState.REVOKED),
        )
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

    private fun identity(observerHandle: String?, state: IdentityState): PairedHome =
        PairedHome(
            instanceId = "home",
            homeLabel = "Home",
            relayOrigin = null,
            caChainFingerprint = "sha256:ca",
            clientCertFingerprint = "sha256:client",
            observerHandle = observerHandle,
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
