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
import app.solstone.core.pl.browser.BrowserRequestBodySource
import app.solstone.core.pl.browser.BrowserResponseSink
import app.solstone.core.pl.browser.JournalBrowserIdentityException
import app.solstone.core.pl.browser.JournalBrowserUpstream
import app.solstone.platform.identity.file.FileIdentityMutator
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalBrowserUpstreamAdapterStreamingTest {

    private fun credential() = ClientCredential(TEST_PRIVATE_KEY_PEM, TEST_CLIENT_CERT_PEM, listOf(TEST_ROOT_CERT_PEM))

    private fun identity(
        state: IdentityState = IdentityState.PAIRED,
        relayOrigin: String? = null,
        deviceToken: String? = null,
    ): PairedHome {
        val fingerprint = "sha256:" + app.solstone.core.crypto.sha256Hex(app.solstone.core.crypto.certificateFromPem(TEST_CLIENT_CERT_PEM).encoded)
        return PairedHome(
            instanceId = "home",
            homeLabel = "Home",
            relayOrigin = relayOrigin,
            caChainFingerprint = "ca_fp",
            clientCertFingerprint = fingerprint,
            observerHandle = "handle",
            deviceToken = deviceToken,
            expiresAt = null,
            state = state,
        )
    }

    private class FakeEndpointStore(private var endpoint: DirectEndpoint?) : EndpointStore {
        override fun load(): DirectEndpoint? = endpoint
        override fun save(directEndpoint: DirectEndpoint) { endpoint = directEndpoint }
        override fun clear() { endpoint = null }
    }

    private class FakeCredentialStore(private var cred: ClientCredential?) : ClientCredentialStore {
        override fun load(): ClientCredential? = cred
        override fun save(credential: ClientCredential) { cred = credential }
        override fun clear() { cred = null }
    }

    private class FakeIdentityStore(private var home: PairedHome?) : IdentityStore {
        override fun load(): PairedHome? = home
        override fun save(identity: PairedHome) { home = identity }
        override fun clear() { home = null }
    }

    @Test
    fun defaultAdapterUsesProductionOpeners() {
        val serverSocket = ServerSocket(0).apply { soTimeout = 3000 }
        val port = serverSocket.localPort
        val firstTlsByte = AtomicInteger(-1)
        val serverDone = CountDownLatch(1)

        val serverThread = Thread {
            try {
                val sock = serverSocket.accept()
                try {
                    firstTlsByte.set(sock.getInputStream().read())
                } finally {
                    sock.close()
                }
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
                serverDone.countDown()
            }
        }
        serverThread.start()

        val cred = credential()
        val ident = identity(state = IdentityState.PAIRED)
        val credStore = FakeCredentialStore(cred)
        val identStore = FakeIdentityStore(ident)
        val endpointStore = FakeEndpointStore(DirectEndpoint("127.0.0.1", port))

        val adapter = JournalBrowserUpstreamAdapter(
            endpointStore = endpointStore,
            credentialStore = credStore,
            identityStore = identStore,
            mutator = FileIdentityMutator(identStore),
        )

        try {
            assertFailsWith<IOException> {
                adapter.open()
            }
        } finally {
            serverDone.await(3, TimeUnit.SECONDS)
            serverThread.join(1000)
        }

        assertEquals(0x16, firstTlsByte.get(), "Default adapter should invoke production openAuthenticatedClient sending TLS ClientHello (0x16)")
    }

    @Test
    fun plantedOpenerIsDetectableAsFake() {
        var fakeDirectCalled = false
        val ident = identity(state = IdentityState.PAIRED, relayOrigin = "https://relay.example", deviceToken = "tok")
        val identStore = FakeIdentityStore(ident)
        val mut = FileIdentityMutator(identStore)
        val adapter = JournalBrowserUpstreamAdapter(
            endpointStore = FakeEndpointStore(DirectEndpoint("127.0.0.1", 9999)),
            credentialStore = FakeCredentialStore(credential()),
            identityStore = identStore,
            mutator = mut,
            directClientOpener = { _, _ ->
                fakeDirectCalled = true
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?) = BrowserHttpResponse(200, emptyList(), ByteArray(0))
                    override fun requestStreaming(method: String, path: String, headers: List<Pair<String, String>>, bodySource: BrowserRequestBodySource?, responseSink: BrowserResponseSink) {}
                    override fun close() {}
                }
            },
        )

        val upstream = adapter.open()
        assertTrue(fakeDirectCalled, "Injected fake opener was called")
        upstream.close()
    }

    @Test
    fun trustRefusalStillJournalBrowserIdentityExceptionAndStoresUntouched() {
        val credStore = FakeCredentialStore(null)
        val identStore = FakeIdentityStore(identity(state = IdentityState.REVOKED))
        val endpointStore = FakeEndpointStore(null)

        val adapter = JournalBrowserUpstreamAdapter(
            endpointStore = endpointStore,
            credentialStore = credStore,
            identityStore = identStore,
            mutator = FileIdentityMutator(identStore),
        )

        assertFailsWith<JournalBrowserIdentityException> {
            adapter.open()
        }

        assertNull(credStore.load())
        assertEquals(IdentityState.REVOKED, identStore.load()?.state)
        assertNull(endpointStore.load())
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
