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
import app.solstone.core.pl.browser.BrowserHttpResponse
import app.solstone.core.pl.browser.JournalBrowserIdentityException
import app.solstone.core.pl.browser.JournalBrowserUpstream
import app.solstone.platform.identity.file.FileIdentityMutator
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JournalBrowserUpstreamAdapterTest {

    @Test
    fun selectsDirectOpenerWhenEndpointStoreHasEndpoint() {
        val endpoint = DirectEndpoint("192.0.2.1", 7657)
        val endpointStore = FakeEndpointStore(endpoint)
        val credentialStore = FakeCredentialStore(credential())
        val initialIdentity = identity(state = IdentityState.PAIRED, relayOrigin = "https://relay.example", deviceToken = "tok")
        val identityStore = FakeIdentityStore(initialIdentity)
        val mutator = FileIdentityMutator(identityStore)

        var directOpened = false
        var relayOpened = false

        val adapter = JournalBrowserUpstreamAdapter(
            endpointStore = endpointStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            mutator = mutator,
            directClientOpener = { ep, cred ->
                directOpened = true
                assertEquals(endpoint, ep)
                FakeJournalBrowserUpstream()
            },
            relayClientOpener = { origin, inst, tok, cred ->
                relayOpened = true
                FakeJournalBrowserUpstream()
            },
        )

        val upstream = adapter.open()
        assertTrue(directOpened)
        assertTrue(!relayOpened)
        assertTrue(adapter.isAccessStillCurrent())
        upstream.close()
    }

    @Test
    fun selectsRelayOpenerWhenEndpointStoreIsEmpty() {
        val endpointStore = FakeEndpointStore(null)
        val credentialStore = FakeCredentialStore(credential())
        val initialIdentity = identity(state = IdentityState.PAIRED, relayOrigin = "https://relay.example", deviceToken = "tok123")
        val identityStore = FakeIdentityStore(initialIdentity)
        val mutator = FileIdentityMutator(identityStore)

        var directOpened = false
        var relayOpened = false

        val adapter = JournalBrowserUpstreamAdapter(
            endpointStore = endpointStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            mutator = mutator,
            directClientOpener = { _, _ ->
                directOpened = true
                FakeJournalBrowserUpstream()
            },
            relayClientOpener = { origin, inst, tok, cred ->
                relayOpened = true
                assertEquals("https://relay.example", origin)
                assertEquals("home", inst)
                assertEquals("tok123", tok)
                FakeJournalBrowserUpstream()
            },
        )

        val upstream = adapter.open()
        assertTrue(!directOpened)
        assertTrue(relayOpened)
        assertTrue(adapter.isAccessStillCurrent())
        upstream.close()
    }

    @Test
    fun failsToOpenWhenUnpairedOrMissingCredentialsWithIdentityException() {
        val endpointStore = FakeEndpointStore(null)
        val credentialStore = FakeCredentialStore(null)
        val initialIdentity = identity(state = IdentityState.REVOKED)
        val identityStore = FakeIdentityStore(initialIdentity)
        val mutator = FileIdentityMutator(identityStore)

        val adapter = JournalBrowserUpstreamAdapter(
            endpointStore = endpointStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            mutator = mutator,
            directClientOpener = { _, _ -> FakeJournalBrowserUpstream() },
            relayClientOpener = { _, _, _, _ -> FakeJournalBrowserUpstream() },
        )

        assertFailsWith<JournalBrowserIdentityException> {
            adapter.open()
        }
        assertEquals(false, adapter.isAccessStillCurrent())
    }

    @Test
    fun mapsTrustRefusalToIdentityException() {
        val endpoint = DirectEndpoint("192.0.2.1", 7657)
        val endpointStore = FakeEndpointStore(endpoint)
        val credentialStore = FakeCredentialStore(credential())
        val initialIdentity = identity(state = IdentityState.PAIRED)
        val identityStore = FakeIdentityStore(initialIdentity)
        val mutator = FileIdentityMutator(identityStore)

        val adapter = JournalBrowserUpstreamAdapter(
            endpointStore = endpointStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            mutator = mutator,
            directClientOpener = { _, _ -> throw SSLException("Certificate verification failed") },
            relayClientOpener = { _, _, _, _ -> FakeJournalBrowserUpstream() },
        )

        assertFailsWith<JournalBrowserIdentityException> {
            adapter.open()
        }
    }

    private class FakeJournalBrowserUpstream : JournalBrowserUpstream {
        override val isPoisoned: Boolean = false
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
            return BrowserHttpResponse(200, emptyList(), ByteArray(0))
        }
        override fun close() {}
    }

    private fun credential(): ClientCredential =
        ClientCredential(TEST_PRIVATE_KEY_PEM, TEST_CLIENT_CERT_PEM, listOf(TEST_ROOT_CERT_PEM))

    private fun identity(
        state: IdentityState,
        relayOrigin: String? = null,
        deviceToken: String? = null,
    ): PairedHome {
        val fingerprint = "sha256:" + app.solstone.core.crypto.sha256Hex(app.solstone.core.crypto.certificateFromPem(TEST_CLIENT_CERT_PEM).encoded)
        return PairedHome(
            instanceId = "home",
            homeLabel = "Home",
            relayOrigin = relayOrigin,
            caChainFingerprint = "sha256:ca",
            clientCertFingerprint = fingerprint,
            observerHandle = "handle",
            deviceToken = deviceToken,
            expiresAt = null,
            state = state,
        )
    }

    private class FakeEndpointStore(private var endpoint: DirectEndpoint?) : EndpointStore {
        override fun save(endpoint: DirectEndpoint) { this.endpoint = endpoint }
        override fun load(): DirectEndpoint? = endpoint
        override fun clear() { endpoint = null }
    }

    private class FakeCredentialStore(private var credential: ClientCredential?) : ClientCredentialStore {
        override fun save(credential: ClientCredential) { this.credential = credential }
        override fun load(): ClientCredential? = credential
        override fun clear() { credential = null }
    }

    private class FakeIdentityStore(private var identity: PairedHome?) : IdentityStore {
        override fun save(home: PairedHome) { identity = home }
        override fun load(): PairedHome? = identity
        override fun clear() { identity = null }
    }
}

private const val TEST_PRIVATE_KEY_PEM = """-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg+qMFOOvzSEoSsmnF
1C4hRkGycvgh6JYUc+E0clyh8RyhRANCAAQbqfHWrZacqrd8shfL9DAF/QLEyQcd
A+ck4mg5niVnRf4i1CwTu7+ZI0jzKoc1/uNS3nwgN43qD+ZRSOzlQTfm
-----END PRIVATE KEY-----
"""

private const val TEST_CLIENT_CERT_PEM = """-----BEGIN CERTIFICATE-----
MIIBgDCCASagAwIBAgIUPHnu1UmhAJQEWvnkfnhJysFbhvMwCgYIKoZIzj0EAwIw
HTEbMBkGA1UEAwwSc29sc3RvbmUtdGVzdC1yb290MB4XDTI2MDYxNjIwMTgyN1oX
DTM2MDYxMzIwMTgyN1owHzEdMBsGA1UEAwwUc29sc3RvbmUtdGVzdC1jbGllbnQw
WTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQbqfHWrZacqrd8shfL9DAF/QLEyQcd
A+ck4mg5niVnRf4i1CwTu7+ZI0jzKoc1/uNS3nwgN43qD+ZRSOzlQTfmo0IwQDAd
BgNVHQ4EFgQUH6ADnRjFChv7N3ETgd+zYI2ThCUwHwYDVR0jBBgwFoAU/MNAu41T
MZkbAIOVdCN4RBHOi+QwCgYIKoZIzj0EAwIDSAAwRQIgLD/R7i5e/wZ0djNR4uz6
+9OhQ/YaEeg/9+PwE4AblE4CIQDvxF8TolKPOT1Aud4GvqSPC93WjY9nRsaNJeL8
RssZUQ==
-----END CERTIFICATE-----
"""

private const val TEST_ROOT_CERT_PEM = """-----BEGIN CERTIFICATE-----
MIIBjjCCATWgAwIBAgIUSUGOs8jjRKxvugydDhY6NaOCITswCgYIKoZIzj0EAwIw
HTEbMBkGA1UEAwwSc29sc3RvbmUtdGVzdC1yb290MB4XDTI2MDYxNjIwMTgyN1oX
DTM2MDYxMzIwMTgyN1owHTEbMBkGA1UEAwwSc29sc3RvbmUtdGVzdC1yb290MFkw
EwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEYtpHGllY66cH2hexTmmaWdO9GQfk48y9
hhl6IqHmMgtw3xMPiGCcQKIjZQmOrFWZMtBkxsmpF7IUJ3clVTLs3aNTMFEwHQYD
VR0OBBYEFPzDQLuNUzGZGwCDlXQjeEQRzovkMB8GA1UdIwQYMBaAFPzDQLuNUzGZ
GwCDlXQjeEQRzovkMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDRwAwRAIg
V9jU1ex9tPW047hf3YcLaUCVtEi7sCzJN1tWm+Ao9AMCIEsE+l+FIGFYPmUDZOgJ
myy/KG7HMOZ3GDzOlcdOZGHs
-----END CERTIFICATE-----
"""
