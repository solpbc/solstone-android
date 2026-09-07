// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.HttpResponse
import app.solstone.platform.identity.file.FileIdentityMutator
import app.solstone.platform.identity.file.FileIdentityStore
import app.solstone.platform.identity.file.SecretProtector
import app.solstone.platform.pl.transport.conscrypt.HttpsPoster
import app.solstone.platform.pl.transport.conscrypt.RelayDialWaitingException
import app.solstone.platform.pl.transport.conscrypt.RelayWebSocketClosedException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RelayTokenMaintenanceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class NoOpSecretProtector : SecretProtector {
        override fun protect(plaintext: ByteArray): ByteArray = plaintext
        override fun unprotect(ciphertext: ByteArray): ByteArray = ciphertext
    }

    private fun createStore(initial: PairedHome? = null): FileIdentityStore {
        val file = File(temp.root, "identity.tsv")
        val store = FileIdentityStore(file, NoOpSecretProtector())
        if (initial != null) {
            store.save(initial)
        }
        return store
    }

    @Test
    fun maintainSkipsTokenBelowRefreshThreshold() {
        val store = createStore(home())
        val mutator = FileIdentityMutator(store)
        val poster = FakePoster()
        val transport = transport(token = jwt(iat = 100, exp = 200))

        val result = maintainRelayToken(home(), transport, poster, mutator, nowEpochMs = 150_000L)

        assertEquals(transport, assertIs<RelayTokenResult.Ready>(result).transport)
        assertEquals(0, poster.calls)
        assertEquals(home(), store.load())
    }

    @Test
    fun maintainPersistsAndReturnsRefreshedToken() {
        val store = createStore(home())
        val mutator = FileIdentityMutator(store)
        val newToken = jwt(iat = 100, exp = 1767225600)
        val poster = FakePoster(HttpResponse(200, emptyMap(), """{"device_token":"$newToken","protocol_version":2,"expires_at":"2026-01-01T00:00:00Z"}""".toByteArray()))

        val result = maintainRelayToken(home(), transport(), poster, mutator, nowEpochMs = 181_000L)

        assertEquals(newToken, assertIs<RelayTokenResult.Ready>(result).transport.deviceToken)
        assertEquals(newToken, store.load()?.deviceToken)
        assertEquals("2026-01-01T00:00:00Z", store.load()?.expiresAt)
        assertEquals(1, poster.calls)
    }

    @Test
    fun maintainReconnectDoesNotPersist() {
        val store = createStore(home())
        val mutator = FileIdentityMutator(store)
        val poster = FakePoster(HttpResponse(401, emptyMap(), """{"reason":"expired"}""".toByteArray()))

        val result = maintainRelayToken(home(), transport(), poster, mutator, nowEpochMs = 181_000L)

        assertEquals(RelayTokenResult.ReconnectNeeded, result)
        assertEquals(home(), store.load())
    }

    @Test
    fun maintainTransientKeepsOldToken() {
        val store = createStore(home())
        val mutator = FileIdentityMutator(store)
        val transport = transport()

        val result = maintainRelayToken(home(), transport, FakePoster(HttpResponse(500, emptyMap(), ByteArray(0))), mutator, nowEpochMs = 181_000L)

        assertEquals(transport, assertIs<RelayTokenResult.Ready>(result).transport)
        assertEquals(home(), store.load())
    }

    @Test
    fun reactiveDialSuccessDoesNotRefresh() {
        val store = createStore(home())
        val mutator = FileIdentityMutator(store)
        val dial = FakeDial(SyncOutcome.SUCCESS)
        val oldToken = jwt(iat = 100, exp = 200)

        val result = dialWithReactiveRefresh(home(oldToken), transport(token = oldToken), FakePoster(), mutator, dial)

        assertEquals(SyncOutcome.SUCCESS, result)
        assertEquals(listOf(oldToken), dial.tokens)
    }

    @Test
    fun reactive4401RefreshesOncePersistsAndRedials() {
        val oldToken = jwt(iat = 100, exp = 2000000000)
        val newToken = jwt(iat = 100, exp = 2000000000)
        val store = createStore(home(oldToken))
        val mutator = FileIdentityMutator(store)
        val poster = FakePoster(HttpResponse(200, emptyMap(), """{"device_token":"$newToken","protocol_version":2,"expires_at":"2033-05-18T03:33:20Z"}""".toByteArray()))
        val dial = FakeDial(Close(4401), SyncOutcome.SUCCESS)

        val result = dialWithReactiveRefresh(home(oldToken), transport(token = oldToken), poster, mutator, dial)

        assertEquals(SyncOutcome.SUCCESS, result)
        assertEquals(listOf(oldToken, newToken), dial.tokens)
        assertEquals(newToken, store.load()?.deviceToken)
        assertEquals("2033-05-18T03:33:20Z", store.load()?.expiresAt)
        assertEquals(1, poster.calls)
    }

    @Test
    fun reactiveSecond4401RetriesWithoutLoop() {
        val oldToken = jwt(iat = 100, exp = 2000000000)
        val newToken = jwt(iat = 100, exp = 2000000000)
        val store = createStore(home(oldToken))
        val mutator = FileIdentityMutator(store)
        val poster = FakePoster(HttpResponse(200, emptyMap(), """{"device_token":"$newToken","protocol_version":2}""".toByteArray()))
        val dial = FakeDial(Close(4401), Close(4401))

        val result = dialWithReactiveRefresh(home(oldToken), transport(token = oldToken), poster, mutator, dial)

        assertEquals(SyncOutcome.RETRY, result)
        assertEquals(listOf(oldToken, newToken), dial.tokens)
        assertEquals(1, poster.calls)
    }

    @Test
    fun reactive4401ReconnectFails() {
        val oldToken = jwt(iat = 100, exp = 2000000000)
        val store = createStore(home(oldToken))
        val mutator = FileIdentityMutator(store)
        val result = dialWithReactiveRefresh(
            home(oldToken),
            transport(token = oldToken),
            FakePoster(HttpResponse(401, emptyMap(), """{"reason":"expired"}""".toByteArray())),
            mutator,
            FakeDial(Close(4401)),
        )

        assertEquals(SyncOutcome.FAILURE, result)
    }

    @Test
    fun reactive4401TransientRetries() {
        val oldToken = jwt(iat = 100, exp = 2000000000)
        val store = createStore(home(oldToken))
        val mutator = FileIdentityMutator(store)
        val result = dialWithReactiveRefresh(
            home(oldToken),
            transport(token = oldToken),
            FakePoster(HttpResponse(500, emptyMap(), ByteArray(0))),
            mutator,
            FakeDial(Close(4401)),
        )

        assertEquals(SyncOutcome.RETRY, result)
    }

    @Test
    fun reactiveNon4401RetriesWithoutRefresh() {
        val oldToken = jwt(iat = 100, exp = 2000000000)
        val store = createStore(home(oldToken))
        val mutator = FileIdentityMutator(store)
        val poster = FakePoster()
        val dial = FakeDial(Close(4403))
        val logs = mutableListOf<String>()

        val result = dialWithReactiveRefresh(
            home(oldToken),
            transport(token = oldToken),
            poster,
            mutator,
            dial,
            log = { message, _ -> logs += message },
        )

        assertEquals(SyncOutcome.RETRY, result)
        assertEquals(0, poster.calls)
        assertEquals(listOf(oldToken), dial.tokens)
        assertEquals(listOf("relay websocket closed"), logs)
    }

    @Test
    fun waitingExceptionPropagatesWithoutTokenRefresh() {
        val oldToken = jwt(iat = 100, exp = 2000000000)
        val store = createStore(home(oldToken))
        val mutator = FileIdentityMutator(store)
        val poster = FakePoster()
        val dial = FakeDial(RelayDialWaitingException(30_000L))

        assertFailsWith<RelayDialWaitingException> {
            dialWithReactiveRefresh(home(oldToken), transport(token = oldToken), poster, mutator, dial)
        }

        assertEquals(0, poster.calls)
        assertEquals(listOf(oldToken), dial.tokens)
        assertEquals(home(oldToken), store.load())
    }

    private class FakePoster(
        private val response: HttpResponse = HttpResponse(200, emptyMap(), """{"device_token":"${jwt(100, 2000000000)}","protocol_version":2}""".toByteArray()),
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

    private fun home(token: String = jwt(iat = 100, exp = 200)): PairedHome =
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

    private companion object {
        const val ORIGIN = "https://link.solstone.app"

        fun jwt(iat: Long, exp: Long): String {
            val payload = """{"iss":"https://link.solstone.app","sub":"instance:home","aud":"spl-relay","scope":"session.dial","ver":2,"instance_id":"home","iat":$iat,"exp":$exp,"jti":"test-jti"}"""
            val enc = Base64.getUrlEncoder().withoutPadding()
            return "${enc.encodeToString("{}".toByteArray())}.${enc.encodeToString(payload.toByteArray())}.sig"
        }
    }
}
