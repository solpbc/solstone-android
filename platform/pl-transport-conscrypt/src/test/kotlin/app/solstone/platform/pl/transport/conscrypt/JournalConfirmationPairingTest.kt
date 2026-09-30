// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.HttpResponse
import org.junit.After
import org.junit.Before
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JournalConfirmationPairingTest {

    @Before
    fun setUp() {
        JournalConfirmationPolicy.consults = true
    }

    @After
    fun tearDown() {
        JournalConfirmationPolicy.consults = true
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

    @Test
    fun consultingProcessNullConfirmationThrowsBeforeInstall() {
        val identityStore = InMemoryIdentityStore(null)
        val credStore = InMemoryCredentialStore(null)
        val endpointStore = InMemoryEndpointStore(null)
        val publisher = FakePairingPublisher(identityStore, credStore, endpointStore)

        assertFailsWith<IllegalStateException> {
            persistOrReturnDirectPairResult(
                home = sampleHome("home-1"),
                credential = sampleCredential("home-1"),
                endpoint = DirectEndpoint("10.0.0.2", 7657),
                handshakePinned = true,
                pairStatus = 200,
                credentialStore = credStore,
                identityStore = identityStore,
                endpointStore = endpointStore,
                statusProbe = { _, _ -> HttpResponse(200, emptyMap(), ByteArray(0)) },
                publisher = publisher,
                confirmation = null,
            )
        }

        assertIs<PairingGraphSnapshot.Absent>(publisher.currentSnapshot())
        assertEquals(null, identityStore.load())
        assertEquals(null, credStore.load())
    }

    @Test
    fun consultingProcessSettleThrowsNothingCommitted() {
        val identityStore = InMemoryIdentityStore(null)
        val credStore = InMemoryCredentialStore(null)
        val endpointStore = InMemoryEndpointStore(null)
        val publisher = FakePairingPublisher(identityStore, credStore, endpointStore)
        val confirmation = FakeJournalConfirmationStore(throwOnSettle = true)

        assertFailsWith<IOException> {
            persistOrReturnDirectPairResult(
                home = sampleHome("home-1"),
                credential = sampleCredential("home-1"),
                endpoint = DirectEndpoint("10.0.0.2", 7657),
                handshakePinned = true,
                pairStatus = 200,
                credentialStore = credStore,
                identityStore = identityStore,
                endpointStore = endpointStore,
                statusProbe = { _, _ -> HttpResponse(200, emptyMap(), ByteArray(0)) },
                publisher = publisher,
                confirmation = confirmation,
            )
        }

        assertIs<PairingGraphSnapshot.Absent>(publisher.currentSnapshot())
        assertEquals(null, identityStore.load())
        assertEquals(null, credStore.load())
    }

    @Test
    fun optOutStoreSettleThrowsDirectStatusProbeThrowsPairingStillCommits() {
        JournalConfirmationPolicy.optOut()
        val identityStore = InMemoryIdentityStore(null)
        val credStore = InMemoryCredentialStore(null)
        val endpointStore = InMemoryEndpointStore(null)
        val publisher = FakePairingPublisher(identityStore, credStore, endpointStore)
        val confirmation = FakeJournalConfirmationStore(throwOnSettle = true)

        assertFailsWith<IOException> {
            persistOrReturnDirectPairResult(
                home = sampleHome("home-1"),
                credential = sampleCredential("home-1"),
                endpoint = DirectEndpoint("10.0.0.2", 7657),
                handshakePinned = true,
                pairStatus = 200,
                credentialStore = credStore,
                identityStore = identityStore,
                endpointStore = endpointStore,
                statusProbe = { _, _ -> throw IOException("network error") },
                publisher = publisher,
                confirmation = confirmation,
            )
        }

        val committed = assertIs<PairingGraphSnapshot.Committed>(publisher.currentSnapshot())
        assertEquals("home-1", committed.home.instanceId)
        assertEquals("home-1", identityStore.load()?.instanceId)
    }
}
