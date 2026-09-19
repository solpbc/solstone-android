// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.identity.JournalMark
import app.solstone.core.identity.JournalMarkIcon
import app.solstone.core.identity.JournalMarkRecord
import app.solstone.core.identity.JournalMarkStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.JournalIdentityRefreshCoordinator
import app.solstone.core.pl.PlHttpClient
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectAndRelayPairMarkHookTest {

    private class RecordingMarkStore : JournalMarkStore {
        var currentRecord: JournalMarkRecord? = null
        var clearCount = 0

        override fun load(): JournalMarkRecord? = currentRecord

        override fun save(record: JournalMarkRecord) {
            currentRecord = record
        }

        override fun clear() {
            currentRecord = null
            clearCount++
        }
    }

    private class RecordingMarkCoordinator(
        store: JournalMarkStore,
    ) : JournalIdentityRefreshCoordinator(store) {
        val usableCalls = AtomicInteger(0)
        val identityChangedCalls = AtomicInteger(0)
        val pairingChangedCalls = AtomicInteger(0)
        var lastInstanceId: String? = null

        override fun onUsableConnection(
            instanceId: String,
            pairingMatches: () -> Boolean,
            openClient: () -> PlHttpClient,
        ) {
            usableCalls.incrementAndGet()
            lastInstanceId = instanceId
            // Do NOT invoke openClient() to avoid dialing network
        }

        override fun onIdentityChanged() {
            super.onIdentityChanged()
            identityChangedCalls.incrementAndGet()
        }

        override fun onPairingChanged() {
            super.onPairingChanged()
            pairingChangedCalls.incrementAndGet()
        }
    }

    private class InMemoryIdentityStore(var home: PairedHome? = null) : IdentityStore {
        override fun load(): PairedHome? = home
        override fun save(pairedHome: PairedHome) { home = pairedHome }
        override fun clear() { home = null }
    }

    private class InMemoryCredentialStore(var cred: ClientCredential? = null) : ClientCredentialStore {
        override fun load(): ClientCredential? = cred
        override fun save(value: ClientCredential) { cred = value }
        override fun clear() { cred = null }
    }

    private class InMemoryEndpointStore(var ep: DirectEndpoint? = null) : EndpointStore {
        override fun load(): DirectEndpoint? = ep
        override fun save(endpoint: DirectEndpoint) { ep = endpoint }
        override fun clear() { ep = null }
    }

    private fun sampleHome(instanceId: String) = PairedHome(
        instanceId = instanceId,
        homeLabel = "Home $instanceId",
        relayOrigin = null,
        caChainFingerprint = "ca-fp",
        clientCertFingerprint = "cert-fp",
        observerHandle = null,
        deviceToken = null,
        expiresAt = null,
        state = IdentityState.PAIRED,
    )

    private fun sampleCredential(instanceId: String) = ClientCredential(
        privateKeyPem = "key-$instanceId",
        clientCertPem = "cert-$instanceId",
        caChainPem = listOf("ca-$instanceId"),
    )

    private fun persistOrReturnDirectPairResult(
        home: PairedHome,
        credential: ClientCredential,
        endpoint: DirectEndpoint,
        handshakePinned: Boolean,
        pairStatus: Int,
        credentialStore: ClientCredentialStore,
        identityStore: IdentityStore,
        endpointStore: EndpointStore,
        statusProbe: (DirectEndpoint, ClientCredential) -> HttpResponse,
        journalIdentityCoordinator: JournalIdentityRefreshCoordinator? = null,
        journalMarkStore: JournalMarkStore? = null,
        publisher: app.solstone.core.identity.PairingPublisher? = null,
    ): PairProbeResult {
        val pub = publisher ?: FakePairingPublisher(
            identityStore = identityStore,
            credentialStore = credentialStore,
            endpointStore = endpointStore,
        )
        return app.solstone.platform.pl.transport.conscrypt.persistOrReturnDirectPairResult(
            home = home,
            credential = credential,
            endpoint = endpoint,
            handshakePinned = handshakePinned,
            pairStatus = pairStatus,
            credentialStore = credentialStore,
            identityStore = identityStore,
            endpointStore = endpointStore,
            statusProbe = statusProbe,
            journalIdentityCoordinator = journalIdentityCoordinator,
            journalMarkStore = journalMarkStore,
            publisher = pub,
        )
    }

    @Test
    fun directPairingStatus200NewHomeSubmitsOneUsableConnection() {
        val markStore = RecordingMarkStore()
        val coordinator = RecordingMarkCoordinator(markStore)
        val identityStore = InMemoryIdentityStore(null)
        val credStore = InMemoryCredentialStore(null)
        val endpointStore = InMemoryEndpointStore(null)

        val result = persistOrReturnDirectPairResult(
            home = sampleHome("inst-new"),
            credential = sampleCredential("inst-new"),
            endpoint = DirectEndpoint("10.0.0.2", 7657),
            handshakePinned = true,
            pairStatus = 200,
            credentialStore = credStore,
            identityStore = identityStore,
            endpointStore = endpointStore,
            statusProbe = { _, _ -> HttpResponse(200, emptyMap(), ByteArray(0)) },
            journalIdentityCoordinator = coordinator,
            journalMarkStore = markStore,
        )

        assertEquals(1, coordinator.usableCalls.get())
        assertEquals("inst-new", coordinator.lastInstanceId)
        assertEquals(1, coordinator.identityChangedCalls.get())
        assertEquals(0, coordinator.pairingChangedCalls.get())
    }

    @Test
    fun directPairingStatus200ReconnectingSubmitsOneUsableConnection() {
        val markStore = RecordingMarkStore()
        val coordinator = RecordingMarkCoordinator(markStore)
        val existing = sampleHome("inst-same").copy(state = IdentityState.UNPAIRED)
        val identityStore = InMemoryIdentityStore(existing)
        val credStore = InMemoryCredentialStore(sampleCredential("inst-same"))
        val endpointStore = InMemoryEndpointStore(DirectEndpoint("10.0.0.1", 7657))

        val result = persistOrReturnDirectPairResult(
            home = sampleHome("inst-same"),
            credential = sampleCredential("inst-same"),
            endpoint = DirectEndpoint("10.0.0.2", 7657),
            handshakePinned = true,
            pairStatus = 200,
            credentialStore = credStore,
            identityStore = identityStore,
            endpointStore = endpointStore,
            statusProbe = { _, _ -> HttpResponse(200, emptyMap(), ByteArray(0)) },
            journalIdentityCoordinator = coordinator,
            journalMarkStore = markStore,
        )

        assertEquals(1, coordinator.usableCalls.get())
        assertEquals("inst-same", coordinator.lastInstanceId)
        assertEquals(0, coordinator.identityChangedCalls.get())
        assertEquals(1, coordinator.pairingChangedCalls.get())
    }

    @Test
    fun directPairingStatus200AlreadyConnectedSubmitsOneUsableConnection() {
        val markStore = RecordingMarkStore()
        val coordinator = RecordingMarkCoordinator(markStore)
        val existing = sampleHome("inst-same")
        val identityStore = InMemoryIdentityStore(existing)
        val credStore = InMemoryCredentialStore(sampleCredential("inst-same"))
        val endpointStore = InMemoryEndpointStore(DirectEndpoint("10.0.0.2", 7657))

        val result = persistOrReturnDirectPairResult(
            home = sampleHome("inst-same"),
            credential = sampleCredential("inst-same"),
            endpoint = DirectEndpoint("10.0.0.2", 7657),
            handshakePinned = true,
            pairStatus = 200,
            credentialStore = credStore,
            identityStore = identityStore,
            endpointStore = endpointStore,
            statusProbe = { _, _ -> HttpResponse(200, emptyMap(), ByteArray(0)) },
            journalIdentityCoordinator = coordinator,
            journalMarkStore = markStore,
        )

        assertEquals(1, coordinator.usableCalls.get())
        assertEquals("inst-same", coordinator.lastInstanceId)
    }

    @Test
    fun directPairingNon200SubmitsZeroUsableConnection() {
        val markStore = RecordingMarkStore()
        val coordinator = RecordingMarkCoordinator(markStore)
        val identityStore = InMemoryIdentityStore(null)
        val credStore = InMemoryCredentialStore(null)
        val endpointStore = InMemoryEndpointStore(null)

        val result = persistOrReturnDirectPairResult(
            home = sampleHome("inst-new"),
            credential = sampleCredential("inst-new"),
            endpoint = DirectEndpoint("10.0.0.2", 7657),
            handshakePinned = true,
            pairStatus = 500,
            credentialStore = credStore,
            identityStore = identityStore,
            endpointStore = endpointStore,
            statusProbe = { _, _ -> HttpResponse(500, emptyMap(), ByteArray(0)) },
            journalIdentityCoordinator = coordinator,
            journalMarkStore = markStore,
        )

        assertEquals(0, coordinator.usableCalls.get())
    }

    @Test
    fun directPairingDifferentInstanceClearsMarkStoreImmediately() {
        val markStore = RecordingMarkStore()
        markStore.save(
            JournalMarkRecord(
                instanceId = "old-inst",
                mark = JournalMark(
                    icon1 = JournalMarkIcon("piano", "<path d=\"M0 0\"/>", "blue", "#3b82f6", 45),
                    icon2 = JournalMarkIcon("key", "<path d=\"M0 0\"/>", "purple", "#a855f7", 0),
                    words = listOf("liquefy", "smock"),
                ),
            ),
        )
        val coordinator = RecordingMarkCoordinator(markStore)
        val identityStore = InMemoryIdentityStore(sampleHome("old-inst"))
        val credStore = InMemoryCredentialStore(sampleCredential("old-inst"))
        val endpointStore = InMemoryEndpointStore(null)

        persistOrReturnDirectPairResult(
            home = sampleHome("new-inst"),
            credential = sampleCredential("new-inst"),
            endpoint = DirectEndpoint("10.0.0.2", 7657),
            handshakePinned = true,
            pairStatus = 200,
            credentialStore = credStore,
            identityStore = identityStore,
            endpointStore = endpointStore,
            statusProbe = { _, _ -> HttpResponse(200, emptyMap(), ByteArray(0)) },
            journalIdentityCoordinator = coordinator,
            journalMarkStore = markStore,
        )

        assertNull(markStore.load())
        assertEquals(1, markStore.clearCount)
    }
}
