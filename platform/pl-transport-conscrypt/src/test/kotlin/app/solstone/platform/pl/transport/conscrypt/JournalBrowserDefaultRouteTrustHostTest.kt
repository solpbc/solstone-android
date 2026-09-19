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
import java.io.IOException
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JournalBrowserDefaultRouteTrustHostTest {

    private lateinit var fixture: EphemeralTlsFixture

    @BeforeTest
    fun setUp() {
        fixture = EphemeralTlsFixture.generate()
    }

    private class FakeEndpointStore(private var ep: DirectEndpoint?) : EndpointStore {
        override fun load(): DirectEndpoint? = ep
        override fun save(endpoint: DirectEndpoint) { ep = endpoint }
        override fun clear() { ep = null }
    }

    private class FakeCredentialStore(private var cred: ClientCredential?) : ClientCredentialStore {
        override fun load(): ClientCredential? = cred
        override fun save(credential: ClientCredential) { cred = credential }
        override fun clear() { cred = null }
    }

    private class FakeIdentityStore(private var id: PairedHome?) : IdentityStore {
        override fun load(): PairedHome? = id
        override fun save(identity: PairedHome) { id = identity }
        override fun clear() { id = null }
    }

    @Test
    fun directOpenWithWrongCredentialFailsBeforeMuxOpenAndStoresUntouched() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = true)
        val serverPort = serverSocket.localPort

        val credStore = FakeCredentialStore(fixture.wrongCredential)
        val identStore = FakeIdentityStore(
            PairedHome("inst", "Home", null, "ca_fp", "cert_fp", null, null, null, IdentityState.PAIRED)
        )
        val epStore = FakeEndpointStore(DirectEndpoint("127.0.0.1", serverPort))

        val serverThread = Thread {
            try {
                val sock = serverSocket.accept() as SSLSocket
                try {
                    sock.startHandshake()
                } finally {
                    sock.close()
                }
            } catch (_: Exception) {}
        }
        serverThread.start()

        try {
            assertFailsWith<IOException> {
                val client = openAuthenticatedClient(
                    DirectEndpoint("127.0.0.1", serverPort),
                    fixture.wrongCredential,
                )
                client.request("GET", "/test", emptyMap(), null)
            }
        } finally {
            serverSocket.close()
            serverThread.join(1000)
        }

        // Stores remain untouched
        assertEquals(fixture.wrongCredential, credStore.load())
        assertEquals("inst", identStore.load()?.instanceId)
        assertEquals(serverPort, epStore.load()?.port)
    }

    @Test
    fun relayOpenWithWrongCredentialFailsBeforeMuxOpenAndStoresUntouched() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = false)
        val serverPort = serverSocket.localPort

        val credStore = FakeCredentialStore(fixture.wrongCredential)
        val identStore = FakeIdentityStore(
            PairedHome("inst", "Home", "https://127.0.0.1:$serverPort", "ca_fp", "cert_fp", null, "tok", null, IdentityState.PAIRED)
        )
        val epStore = FakeEndpointStore(null)

        val serverThread = Thread {
            try {
                val sock = serverSocket.accept()
                // Reject immediately so client doesn't wait 30s
                sock.close()
            } catch (_: Exception) {}
        }
        serverThread.start()

        fixture.withClientTrustDefault {
            try {
                assertFailsWith<IOException> {
                    val client = openRelaySyncClient(
                        relayOrigin = "https://127.0.0.1:$serverPort",
                        instanceId = "inst",
                        deviceToken = "tok",
                        credential = fixture.wrongCredential,
                    )
                    client.request("GET", "/test", emptyMap(), null)
                }
            } finally {
                serverSocket.close()
                serverThread.join(1000)
            }
        }

        assertEquals(fixture.wrongCredential, credStore.load())
        assertEquals("inst", identStore.load()?.instanceId)
        assertNull(epStore.load())
    }
}
