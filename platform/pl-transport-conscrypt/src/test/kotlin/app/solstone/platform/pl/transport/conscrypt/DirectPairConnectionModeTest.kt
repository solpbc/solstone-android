// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class DirectPairConnectionModeTest {
    @Test
    fun samePairedInstanceReturnsAlreadyConnectedWithoutOverwritingStores() {
        val existing = home(instanceId = "same", label = "Existing")
        val stores = Stores(existing, endpoint = DirectEndpoint("10.0.0.9", 7657), credential = credential("old"))
        var statusCalls = 0

        val result = persistOrReturnDirectPairResult(
            home = home(instanceId = "same", label = "New"),
            credential = credential("new"),
            endpoint = DirectEndpoint("10.0.0.2", 7657),
            handshakePinned = true,
            pairStatus = 200,
            credentialStore = stores.credentialStore,
            identityStore = stores.identityStore,
            endpointStore = stores.endpointStore,
            statusProbe = { _, _ ->
                statusCalls += 1
                HttpResponse(200, emptyMap(), "ok".toByteArray())
            },
        )

        assertEquals(DirectPairConnectionMode.ALREADY_CONNECTED, result.connectionMode)
        assertEquals(DirectEndpoint("10.0.0.9", 7657), result.endpoint)
        assertEquals(0, statusCalls)
        assertEquals(existing, stores.identityStore.load())
        assertEquals("old", stores.credentialStore.load()?.privateKeyPem)
        assertEquals(DirectEndpoint("10.0.0.9", 7657), stores.endpointStore.load())
    }

    @Test
    fun sameNonPairedInstancePersistsAndMarksReconnecting() {
        val stores = Stores(home(instanceId = "same", state = IdentityState.REVOKED))

        val result = persistOrReturnDirectPairResult(
            home = home(instanceId = "same", label = "New"),
            credential = credential("new"),
            endpoint = DirectEndpoint("10.0.0.2", 7657),
            handshakePinned = true,
            pairStatus = 200,
            credentialStore = stores.credentialStore,
            identityStore = stores.identityStore,
            endpointStore = stores.endpointStore,
            statusProbe = { _, _ -> HttpResponse(200, emptyMap(), "ok".toByteArray()) },
        )

        assertEquals(DirectPairConnectionMode.RECONNECTING, result.connectionMode)
        assertEquals("new", stores.credentialStore.load()?.privateKeyPem)
        assertEquals(IdentityState.PAIRED, stores.identityStore.load()?.state)
        assertEquals(DirectEndpoint("10.0.0.2", 7657), stores.endpointStore.load())
    }

    @Test
    fun differentInstancePersistsAndMarksPairing() {
        val stores = Stores(home(instanceId = "old"))

        val result = persistOrReturnDirectPairResult(
            home = home(instanceId = "new", label = "New"),
            credential = credential("new"),
            endpoint = DirectEndpoint("10.0.0.2", 7657),
            handshakePinned = true,
            pairStatus = 200,
            credentialStore = stores.credentialStore,
            identityStore = stores.identityStore,
            endpointStore = stores.endpointStore,
            statusProbe = { _, _ -> HttpResponse(200, emptyMap(), "ok".toByteArray()) },
        )

        assertEquals(DirectPairConnectionMode.PAIRING, result.connectionMode)
        assertEquals("new", stores.identityStore.load()?.instanceId)
        assertEquals("new", stores.credentialStore.load()?.privateKeyPem)
    }

    private class Stores(
        home: PairedHome? = null,
        endpoint: DirectEndpoint? = null,
        credential: ClientCredential? = null,
    ) {
        val identityStore = FakeIdentityStore(home)
        val endpointStore = FakeEndpointStore(endpoint)
        val credentialStore = FakeCredentialStore(credential)
    }

    private class FakeIdentityStore(private var home: PairedHome?) : IdentityStore {
        override fun save(home: PairedHome) {
            this.home = home
        }

        override fun load(): PairedHome? = home

        override fun clear() {
            home = null
        }
    }

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

    private fun credential(key: String): ClientCredential = ClientCredential(key, "cert", listOf("ca"))

    private fun home(
        instanceId: String,
        label: String = "Home",
        state: IdentityState = IdentityState.PAIRED,
    ): PairedHome =
        PairedHome(
            instanceId = instanceId,
            homeLabel = label,
            relayOrigin = null,
            caChainFingerprint = "sha256:ca",
            clientCertFingerprint = "sha256:client",
            observerHandle = null,
            deviceToken = null,
            expiresAt = null,
            state = state,
        )
}
