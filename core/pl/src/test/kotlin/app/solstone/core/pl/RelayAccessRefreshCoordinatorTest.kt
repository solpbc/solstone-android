// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.AccessMutationResult
import app.solstone.core.identity.IdentityMutator
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayAccessRefreshCoordinatorTest {

    private class FakeMutator(
        var home: PairedHome?,
        var liveEligible: Boolean = true,
    ) : IdentityMutator {
        var disabledLiveCalled = AtomicBoolean(false)
        val mutationGen = AtomicLong(1L)
        var mutateCallback: (() -> Unit)? = null
        var mutateHook: ((expectedPairing: PairingGeneration, expectedAccessMutationGen: Long) -> AccessMutationResult?)? = null

        override fun current(): PairedHome? = home
        override fun currentPairingGeneration(): PairingGeneration? =
            home?.let { PairingGeneration(it.instanceId, it.clientCertFingerprint) }
        override fun currentAccessMutationGen(): Long = mutationGen.get()
        override fun isRelayLiveEligible(): Boolean = liveEligible
        override fun disableRelayLive() {
            liveEligible = false
            disabledLiveCalled.set(true)
        }
        override fun installNewPairing(home: PairedHome): Boolean {
            this.home = home
            liveEligible = home.relayOrigin != null && home.deviceToken != null
            return true
        }
        override fun mutate(
            expectedPairing: PairingGeneration,
            expectedAccessMutationGen: Long,
            transform: (PairedHome) -> PairedHome,
        ): AccessMutationResult {
            mutateHook?.invoke(expectedPairing, expectedAccessMutationGen)?.let { return it }
            val cur = home ?: return AccessMutationResult.Conflict("missing home")
            if (expectedPairing != currentPairingGeneration()) return AccessMutationResult.Conflict("pairing mismatch")
            if (expectedAccessMutationGen != mutationGen.get()) return AccessMutationResult.Conflict("gen mismatch")
            val updated = transform(cur)
            home = updated
            val newGen = mutationGen.incrementAndGet()
            mutateCallback?.invoke()
            return AccessMutationResult.Applied(updated, newGen)
        }
    }

    private class FakePlHttpClient(
        private val handler: (method: String, path: String) -> HttpResponse,
    ) : PlHttpClient {
        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse = handler(method, path)
    }

    private fun createJwt(claimsJson: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString(claimsJson.toByteArray())
        return "$header.$payload.sig"
    }

    @Test
    fun readyResponseMutatesIdentity() {
        val initialHome = PairedHome(
            instanceId = "jid-1",
            homeLabel = "Home",
            relayOrigin = null,
            caChainFingerprint = "sha256:ca1",
            clientCertFingerprint = "sha256:cert1",
            observerHandle = null,
            deviceToken = null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        val mutator = FakeMutator(initialHome)
        val mutateLatch = CountDownLatch(1)
        mutator.mutateCallback = { mutateLatch.countDown() }

        val executor = Executors.newCachedThreadPool()
        val coordinator = RelayAccessRefreshCoordinator(mutator, executor)

        val iat = 1700000000L
        val exp = System.currentTimeMillis() / 1000L + 86400L
        val jwt = createJwt("""
        {
            "iss": "solstone",
            "ver": 2,
            "aud": "spl-relay",
            "scope": "session.dial",
            "instance_id": "jid-1",
            "sub": "instance:jid-1",
            "iat": $iat,
            "exp": $exp,
            "jti": "jti-1"
        }
        """.trimIndent())
        val expiresAt = java.time.Instant.ofEpochSecond(exp).toString()
        val responseJson = """
        {
            "protocol_version": 2,
            "status": "ready",
            "relay_origin": "https://relay.solstone.app",
            "instance_id": "jid-1",
            "device_token": "$jwt",
            "expires_at": "$expiresAt"
        }
        """.trimIndent()

        coordinator.onUsableConnection("jid-1", "sha256:ca1", "sha256:cert1") {
            FakePlHttpClient { _, _ -> HttpResponse(200, emptyMap(), responseJson.toByteArray()) }
        }

        mutateLatch.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        assertEquals("https://relay.solstone.app", mutator.home?.relayOrigin)
        assertEquals(jwt, mutator.home?.deviceToken)
    }

    @Test
    fun notConfiguredDisablesLiveRelayImmediately() {
        val initialHome = PairedHome(
            instanceId = "jid-1",
            homeLabel = "Home",
            relayOrigin = "https://relay.solstone.app",
            caChainFingerprint = "sha256:ca1",
            clientCertFingerprint = "sha256:cert1",
            observerHandle = null,
            deviceToken = "token",
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        val mutator = FakeMutator(initialHome, liveEligible = true)
        val mutateLatch = CountDownLatch(1)
        mutator.mutateCallback = { mutateLatch.countDown() }

        val executor = Executors.newCachedThreadPool()
        val coordinator = RelayAccessRefreshCoordinator(mutator, executor)

        val responseJson = """{"protocol_version": 2, "status": "not_configured"}"""

        coordinator.onUsableConnection("jid-1", "sha256:ca1", "sha256:cert1") {
            FakePlHttpClient { _, _ -> HttpResponse(200, emptyMap(), responseJson.toByteArray()) }
        }

        mutateLatch.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        assertTrue(mutator.disabledLiveCalled.get())
        assertFalse(mutator.isRelayLiveEligible())
        assertEquals(null, mutator.home?.relayOrigin)
        assertEquals(null, mutator.home?.deviceToken)
    }

    @Test
    fun durableClearFailureRecordsPendingClearAndRetriesOnUsableConnection() {
        val initialHome = PairedHome(
            instanceId = "jid-1",
            homeLabel = "Home",
            relayOrigin = "https://relay.solstone.app",
            caChainFingerprint = "sha256:ca1",
            clientCertFingerprint = "sha256:cert1",
            observerHandle = null,
            deviceToken = "token-1",
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        val mutator = FakeMutator(initialHome, liveEligible = true)
        // First mutate fails with PersistenceFailed
        mutator.mutateHook = { _, _ ->
            mutator.mutateHook = null // reset for next attempt
            AccessMutationResult.PersistenceFailed(RuntimeException("disk error"))
        }

        val executor = Executors.newCachedThreadPool()
        val coordinator = RelayAccessRefreshCoordinator(mutator, executor)

        val responseJson = """{"protocol_version": 2, "status": "not_configured"}"""

        coordinator.onUsableConnection("jid-1", "sha256:ca1", "sha256:cert1") {
            FakePlHttpClient { _, _ -> HttpResponse(200, emptyMap(), responseJson.toByteArray()) }
        }

        Thread.sleep(200)
        assertFalse(mutator.isRelayLiveEligible())
        assertEquals(PendingClear(PairingGeneration("jid-1", "sha256:cert1"), 1L), coordinator.pendingClear)
        // Disk still has origin and token from failed clear
        assertEquals("https://relay.solstone.app", mutator.home?.relayOrigin)

        // On next onUsableConnection, pending clear runs and succeeds
        coordinator.onUsableConnection("jid-1", "sha256:ca1", "sha256:cert1") {
            FakePlHttpClient { _, _ -> HttpResponse(503, emptyMap(), ByteArray(0)) }
        }

        Thread.sleep(200)
        assertEquals(null, coordinator.pendingClear)
        assertEquals(null, mutator.home?.relayOrigin)
        assertEquals(null, mutator.home?.deviceToken)

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }

    @Test
    fun pendingClearThenNewerReadyCommitThenLateClearRetryPreservesReady() {
        val initialHome = PairedHome(
            instanceId = "jid-1",
            homeLabel = "Home",
            relayOrigin = "https://relay.solstone.app",
            caChainFingerprint = "sha256:ca1",
            clientCertFingerprint = "sha256:cert1",
            observerHandle = null,
            deviceToken = "token-1",
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        val mutator = FakeMutator(initialHome, liveEligible = true)
        val executor = Executors.newCachedThreadPool()
        val coordinator = RelayAccessRefreshCoordinator(mutator, executor)

        // Set pending clear from a prior failure at gen 1
        coordinator.pendingClear = PendingClear(PairingGeneration("jid-1", "sha256:cert1"), 1L)

        // Apply a newer ready commit directly on mutator (bumping gen to 2)
        val mutateResult = mutator.mutate(PairingGeneration("jid-1", "sha256:cert1"), 1L) {
            it.copy(relayOrigin = "https://new-relay.solstone.app", deviceToken = "new-token")
        }
        assertTrue(mutateResult is AccessMutationResult.Applied)
        assertEquals(2L, mutator.currentAccessMutationGen())

        // Late retry of pending clear
        coordinator.retryPendingClearIfMatching()

        // Pending clear should be dropped and ready credentials preserved
        assertEquals(null, coordinator.pendingClear)
        assertEquals("https://new-relay.solstone.app", mutator.home?.relayOrigin)
        assertEquals("new-token", mutator.home?.deviceToken)

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }

    @Test
    fun unpairAndRepairFencesStaleMutate() {
        val initialHome = PairedHome(
            instanceId = "jid-1",
            homeLabel = "Home",
            relayOrigin = null,
            caChainFingerprint = "sha256:ca1",
            clientCertFingerprint = "sha256:cert1",
            observerHandle = null,
            deviceToken = null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        val mutator = FakeMutator(initialHome)
        val executor = Executors.newCachedThreadPool()
        val coordinator = RelayAccessRefreshCoordinator(mutator, executor)

        val iat = 1700000000L
        val exp = System.currentTimeMillis() / 1000L + 86400L
        val jwt = createJwt("""
        {
            "iss": "solstone",
            "ver": 2,
            "aud": "spl-relay",
            "scope": "session.dial",
            "instance_id": "jid-1",
            "sub": "instance:jid-1",
            "iat": $iat,
            "exp": $exp,
            "jti": "jti-1"
        }
        """.trimIndent())
        val expiresAt = java.time.Instant.ofEpochSecond(exp).toString()
        val responseJson = """
        {
            "protocol_version": 2,
            "status": "ready",
            "relay_origin": "https://relay.solstone.app",
            "instance_id": "jid-1",
            "device_token": "$jwt",
            "expires_at": "$expiresAt"
        }
        """.trimIndent()

        val fetchLatch = CountDownLatch(1)
        val continueLatch = CountDownLatch(1)

        coordinator.onUsableConnection("jid-1", "sha256:ca1", "sha256:cert1") {
            FakePlHttpClient { _, _ ->
                fetchLatch.countDown()
                continueLatch.await(3, TimeUnit.SECONDS)
                HttpResponse(200, emptyMap(), responseJson.toByteArray())
            }
        }

        fetchLatch.await(3, TimeUnit.SECONDS)
        // Identity changed before response processing
        coordinator.onIdentityChanged()
        continueLatch.countDown()

        Thread.sleep(200)
        // Mutate should be fenced and not applied
        assertEquals(null, mutator.home?.relayOrigin)
        assertEquals(null, mutator.home?.deviceToken)

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }
}
