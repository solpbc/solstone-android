// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.HttpResponse
import app.solstone.platform.pl.transport.conscrypt.HttpsPoster
import app.solstone.platform.pl.transport.conscrypt.RelayDialWaitingException
import app.solstone.platform.pl.transport.conscrypt.RelayWebSocketClosedException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RelayTokenMaintenanceTest {
    @Test
    fun maintainSkipsTokenBelowRefreshThreshold() {
        val store = FakeIdentityStore(home())
        val poster = FakePoster()
        val transport = transport(token = jwt(iat = 100, exp = 200))

        val result = maintainRelayToken(home(), transport, poster, store, nowEpochMs = 150_000L)

        assertEquals(transport, assertIs<RelayTokenResult.Ready>(result).transport)
        assertEquals(0, poster.calls)
        assertEquals(home(), store.load())
    }

    @Test
    fun maintainPersistsAndReturnsRefreshedToken() {
        val store = FakeIdentityStore(home())
        val poster = FakePoster(HttpResponse(200, emptyMap(), """{"device_token":"new","expires_at":"2026-01-01T00:00:00Z"}""".toByteArray()))

        val result = maintainRelayToken(home(), transport(), poster, store, nowEpochMs = 181_000L)

        assertEquals("new", assertIs<RelayTokenResult.Ready>(result).transport.deviceToken)
        assertEquals("new", store.load()?.deviceToken)
        assertEquals("2026-01-01T00:00:00Z", store.load()?.expiresAt)
        assertEquals(1, poster.calls)
    }

    @Test
    fun maintainReconnectDoesNotPersist() {
        val store = FakeIdentityStore(home())
        val poster = FakePoster(HttpResponse(401, emptyMap(), """{"reason":"expired"}""".toByteArray()))

        val result = maintainRelayToken(home(), transport(), poster, store, nowEpochMs = 181_000L)

        assertEquals(RelayTokenResult.ReconnectNeeded, result)
        assertEquals(home(), store.load())
    }

    @Test
    fun maintainTransientKeepsOldToken() {
        val store = FakeIdentityStore(home())
        val transport = transport()

        val result = maintainRelayToken(home(), transport, FakePoster(HttpResponse(500, emptyMap(), ByteArray(0))), store, nowEpochMs = 181_000L)

        assertEquals(transport, assertIs<RelayTokenResult.Ready>(result).transport)
        assertEquals(home(), store.load())
    }

    @Test
    fun reactiveDialSuccessDoesNotRefresh() {
        val store = FakeIdentityStore(home())
        val dial = FakeDial(SyncOutcome.SUCCESS)

        val result = dialWithReactiveRefresh(home(), transport(token = "old"), FakePoster(), store, dial)

        assertEquals(SyncOutcome.SUCCESS, result)
        assertEquals(listOf("old"), dial.tokens)
    }

    @Test
    fun reactive4401RefreshesOncePersistsAndRedials() {
        val store = FakeIdentityStore(home())
        val poster = FakePoster(HttpResponse(200, emptyMap(), """{"device_token":"new","expires_at":"2026-01-01T00:00:00Z"}""".toByteArray()))
        val dial = FakeDial(Close(4401), SyncOutcome.SUCCESS)

        val result = dialWithReactiveRefresh(home(), transport(token = "old"), poster, store, dial)

        assertEquals(SyncOutcome.SUCCESS, result)
        assertEquals(listOf("old", "new"), dial.tokens)
        assertEquals("new", store.load()?.deviceToken)
        assertEquals("2026-01-01T00:00:00Z", store.load()?.expiresAt)
        assertEquals(1, poster.calls)
    }

    @Test
    fun reactiveSecond4401RetriesWithoutLoop() {
        val store = FakeIdentityStore(home())
        val poster = FakePoster(HttpResponse(200, emptyMap(), """{"device_token":"new"}""".toByteArray()))
        val dial = FakeDial(Close(4401), Close(4401))

        val result = dialWithReactiveRefresh(home(), transport(token = "old"), poster, store, dial)

        assertEquals(SyncOutcome.RETRY, result)
        assertEquals(listOf("old", "new"), dial.tokens)
        assertEquals(1, poster.calls)
    }

    @Test
    fun reactive4401ReconnectFails() {
        val result = dialWithReactiveRefresh(
            home(),
            transport(token = "old"),
            FakePoster(HttpResponse(401, emptyMap(), """{"reason":"expired"}""".toByteArray())),
            FakeIdentityStore(home()),
            FakeDial(Close(4401)),
        )

        assertEquals(SyncOutcome.FAILURE, result)
    }

    @Test
    fun reactive4401TransientRetries() {
        val result = dialWithReactiveRefresh(
            home(),
            transport(token = "old"),
            FakePoster(HttpResponse(500, emptyMap(), ByteArray(0))),
            FakeIdentityStore(home()),
            FakeDial(Close(4401)),
        )

        assertEquals(SyncOutcome.RETRY, result)
    }

    @Test
    fun reactiveNon4401RetriesWithoutRefresh() {
        val poster = FakePoster()
        val dial = FakeDial(Close(4403))

        val result = dialWithReactiveRefresh(home(), transport(token = "old"), poster, FakeIdentityStore(home()), dial)

        assertEquals(SyncOutcome.RETRY, result)
        assertEquals(0, poster.calls)
        assertEquals(listOf("old"), dial.tokens)
    }

    @Test
    fun waitingExceptionPropagatesWithoutTokenRefresh() {
        val store = FakeIdentityStore(home())
        val poster = FakePoster()
        val dial = FakeDial(RelayDialWaitingException(30_000L))

        assertFailsWith<RelayDialWaitingException> {
            dialWithReactiveRefresh(home(), transport(token = "old"), poster, store, dial)
        }

        assertEquals(0, poster.calls)
        assertEquals(listOf("old"), dial.tokens)
        assertEquals(home(), store.load())
    }

    private class FakePoster(
        private val response: HttpResponse = HttpResponse(200, emptyMap(), """{"device_token":"new"}""".toByteArray()),
    ) : HttpsPoster {
        var calls = 0
        override fun post(url: String, body: ByteArray, headers: Map<String, String>): HttpResponse {
            calls += 1
            return response
        }
    }

    private class FakeDial(vararg private val outcomes: Any) : RelayDial {
        val tokens = mutableListOf<String>()
        private var index = 0

        override fun dial(transport: SyncTransport.Relay): SyncOutcome {
            tokens += transport.deviceToken
            val outcome = outcomes.getOrElse(index++) { SyncOutcome.SUCCESS }
            if (outcome is RelayDialWaitingException) {
                throw outcome
            }
            if (outcome is Close) {
                throw RelayWebSocketClosedException(outcome.code, "closed")
            }
            return outcome as SyncOutcome
        }
    }

    private data class Close(val code: Int)

    private class FakeIdentityStore(private var home: PairedHome?) : IdentityStore {
        override fun save(home: PairedHome) {
            this.home = home
        }

        override fun load(): PairedHome? = home

        override fun clear() {
            home = null
        }
    }

    private fun home(token: String = "old"): PairedHome =
        PairedHome(
            instanceId = "home",
            homeLabel = "Home",
            relayOrigin = ORIGIN,
            caChainFingerprint = "sha256:ca",
            clientCertFingerprint = "sha256:client",
            observerHandle = "observer",
            deviceToken = token,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )

    private fun transport(token: String = jwt(iat = 100, exp = 200)): SyncTransport.Relay =
        SyncTransport.Relay(ORIGIN, "home", token)

    private fun jwt(iat: Long, exp: Long): String =
        listOf("{}", """{"iat":$iat,"exp":$exp}""", "sig")
            .map { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }
            .joinToString(".")

    private companion object {
        const val ORIGIN = "https://link.solstone.app"
    }
}
