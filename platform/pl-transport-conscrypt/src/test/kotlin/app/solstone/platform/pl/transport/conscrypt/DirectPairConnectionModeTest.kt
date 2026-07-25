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
import kotlin.test.assertFailsWith

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

    @Test
    fun emptyStoresRetainWritesCompletedBeforeEachFailure() {
        FailureStage.entries.forEach { stage ->
            val stores = Stores(failureStage = stage)

            assertFailsWith<IllegalStateException> { persistWithFailure(stores, stage) }

            when (stage) {
                FailureStage.CREDENTIAL -> {
                    assertEquals(null, stores.credentialStore.load())
                    assertEquals(null, stores.identityStore.load())
                    assertEquals(null, stores.endpointStore.load())
                }
                FailureStage.IDENTITY -> {
                    assertEquals("new", stores.credentialStore.load()?.privateKeyPem)
                    assertEquals(null, stores.identityStore.load())
                    assertEquals(null, stores.endpointStore.load())
                }
                FailureStage.ENDPOINT -> {
                    assertEquals("new", stores.credentialStore.load()?.privateKeyPem)
                    assertEquals("new", stores.identityStore.load()?.instanceId)
                    assertEquals(null, stores.endpointStore.load())
                }
                FailureStage.PROBE -> assertAllNew(stores)
            }
        }
    }

    @Test
    fun preseededStoresAreNeverRolledBackOrClearedAfterFailure() {
        FailureStage.entries.forEach { stage ->
            val oldHome = home(instanceId = "old")
            val oldEndpoint = DirectEndpoint("10.0.0.9", 7657)
            val stores = Stores(
                home = oldHome,
                endpoint = oldEndpoint,
                credential = credential("old"),
                failureStage = stage,
            )

            assertFailsWith<IllegalStateException> { persistWithFailure(stores, stage) }

            when (stage) {
                FailureStage.CREDENTIAL -> {
                    assertEquals("old", stores.credentialStore.load()?.privateKeyPem)
                    assertEquals(oldHome, stores.identityStore.load())
                    assertEquals(oldEndpoint, stores.endpointStore.load())
                }
                FailureStage.IDENTITY -> {
                    assertEquals("new", stores.credentialStore.load()?.privateKeyPem)
                    assertEquals(oldHome, stores.identityStore.load())
                    assertEquals(oldEndpoint, stores.endpointStore.load())
                }
                FailureStage.ENDPOINT -> {
                    assertEquals("new", stores.credentialStore.load()?.privateKeyPem)
                    assertEquals("new", stores.identityStore.load()?.instanceId)
                    assertEquals(oldEndpoint, stores.endpointStore.load())
                }
                FailureStage.PROBE -> assertAllNew(stores)
            }
        }
    }

    @Test
    fun credentialStoreFailurePropagatesWithoutSuccess() {
        assertPersistFailure(FailureStage.CREDENTIAL, "credential save failed")
    }

    @Test
    fun identityStoreFailurePropagatesWithoutSuccess() {
        assertPersistFailure(FailureStage.IDENTITY, "identity save failed")
    }

    @Test
    fun endpointStoreFailurePropagatesWithoutSuccess() {
        assertPersistFailure(FailureStage.ENDPOINT, "endpoint save failed")
    }

    @Test
    fun statusProbeFailurePropagatesWithoutSuccess() {
        assertPersistFailure(FailureStage.PROBE, "probe failed")
    }

    private fun assertPersistFailure(stage: FailureStage, expectedMessage: String) {
        val failure = assertFailsWith<IllegalStateException> {
            persistWithFailure(Stores(failureStage = stage), stage)
        }
        assertEquals(expectedMessage, failure.message)
        assertEquals(false, failure.message == "all pair candidates exhausted")
    }

    private fun persistWithFailure(stores: Stores, stage: FailureStage) {
        persistOrReturnDirectPairResult(
            home = home(instanceId = "new", label = "New"),
            credential = credential("new"),
            endpoint = DirectEndpoint("10.0.0.2", 7657),
            handshakePinned = true,
            pairStatus = 200,
            credentialStore = stores.credentialStore,
            identityStore = stores.identityStore,
            endpointStore = stores.endpointStore,
            statusProbe = { _, _ ->
                if (stage == FailureStage.PROBE) throw IllegalStateException("probe failed")
                HttpResponse(200, emptyMap(), "ok".toByteArray())
            },
        )
    }

    private fun assertAllNew(stores: Stores) {
        assertEquals("new", stores.credentialStore.load()?.privateKeyPem)
        assertEquals("new", stores.identityStore.load()?.instanceId)
        assertEquals(DirectEndpoint("10.0.0.2", 7657), stores.endpointStore.load())
    }

    private enum class FailureStage { CREDENTIAL, IDENTITY, ENDPOINT, PROBE }

    private class Stores(
        home: PairedHome? = null,
        endpoint: DirectEndpoint? = null,
        credential: ClientCredential? = null,
        failureStage: FailureStage? = null,
    ) {
        val identityStore = FakeIdentityStore(home, failureStage == FailureStage.IDENTITY)
        val endpointStore = FakeEndpointStore(endpoint, failureStage == FailureStage.ENDPOINT)
        val credentialStore = FakeCredentialStore(credential, failureStage == FailureStage.CREDENTIAL)
    }

    private class FakeIdentityStore(
        private var home: PairedHome?,
        private val failSave: Boolean = false,
    ) : IdentityStore {
        override fun save(home: PairedHome) {
            if (failSave) throw IllegalStateException("identity save failed")
            this.home = home
        }

        override fun load(): PairedHome? = home

        override fun clear() {
            home = null
        }
    }

    private class FakeEndpointStore(
        private var endpoint: DirectEndpoint?,
        private val failSave: Boolean = false,
    ) : EndpointStore {
        override fun save(endpoint: DirectEndpoint) {
            if (failSave) throw IllegalStateException("endpoint save failed")
            this.endpoint = endpoint
        }

        override fun load(): DirectEndpoint? = endpoint

        override fun clear() {
            endpoint = null
        }
    }

    private class FakeCredentialStore(
        private var credential: ClientCredential?,
        private val failSave: Boolean = false,
    ) : ClientCredentialStore {
        override fun save(credential: ClientCredential) {
            if (failSave) throw IllegalStateException("credential save failed")
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
