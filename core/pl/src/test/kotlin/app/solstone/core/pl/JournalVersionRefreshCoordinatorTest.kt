// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.AccessMutationResult
import app.solstone.core.identity.IdentityMutator
import app.solstone.core.identity.JournalVersionRecord
import app.solstone.core.identity.JournalVersionStore
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalVersionRefreshCoordinatorTest {
    private class FakeStore : JournalVersionStore {
        var savedRecord: JournalVersionRecord? = null
        var onSave: (() -> Unit)? = null

        override fun load(): JournalVersionRecord? = savedRecord

        override fun save(record: JournalVersionRecord) {
            savedRecord = record
            onSave?.invoke()
        }

        override fun clear() {
            savedRecord = null
        }
    }

    private class RoutingFakeClient(
        private val handler: (method: String, path: String, body: ByteArray?) -> HttpResponse,
    ) : PlHttpClient {
        var lastMaxResponseBytes: Int = -1
        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse {
            lastMaxResponseBytes = maxResponseBytes
            return handler(method, path, body)
        }
    }

    @Test
    fun successfulClientsSelfGetAndPutPersistsNameAndVersion() {
        val store = FakeStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val localDesc = ClientReportedDescription(name = "Pixel 8", platform = "android", appId = "app.solstone.phone")
        val getJson = """{"protocol_version":1,"revision":1,"journal":{"name":"Home Journal","version":"1.2.3"},"reported":{"name":"Old Name","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null}""".trimIndent()
        val putJson = """{"protocol_version":1,"revision":2,"reported":{"name":"Pixel 8","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null,"journal":{"name":"Home Journal","version":"1.2.3"}}""".trimIndent()

        val putCalled = AtomicInteger(0)

        coordinator.onUsableConnection("jid-1", "sha256:ca1", "sha256:cert1", localDescriptionProvider = { localDesc }) {
            RoutingFakeClient { method, path, _ ->
                when {
                    method == "GET" && path == "/app/network/api/clients/self" -> HttpResponse(200, emptyMap(), getJson.toByteArray())
                    method == "PUT" && path == "/app/network/api/clients/self" -> {
                        putCalled.incrementAndGet()
                        HttpResponse(200, emptyMap(), putJson.toByteArray())
                    }
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            }
        }

        saved.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertEquals("1.2.3", reading.version)
        assertEquals("Home Journal", reading.name)
        assertEquals(JournalVersionFreshness.CURRENT, reading.freshness)
        assertEquals(1, putCalled.get())
        assertEquals(JournalVersionRecord("jid-1", "sha256:ca1", "1.2.3", "Home Journal"), store.savedRecord)
    }

    @Test
    fun skipsPutWhenReportedMatchesLocalSnapshot() {
        val store = FakeStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val localDesc = ClientReportedDescription(name = "Pixel 8", platform = "android")
        val getJson = """{"protocol_version":1,"revision":1,"journal":{"name":"Home Journal","version":"1.2.3"},"reported":{"name":"Pixel 8","platform":"android","device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null}""".trimIndent()

        val putCalled = AtomicInteger(0)

        coordinator.onUsableConnection("jid-1", "sha256:ca1", "sha256:cert1", localDescriptionProvider = { localDesc }) {
            RoutingFakeClient { method, path, _ ->
                when {
                    method == "GET" && path == "/app/network/api/clients/self" -> HttpResponse(200, emptyMap(), getJson.toByteArray())
                    method == "PUT" && path == "/app/network/api/clients/self" -> {
                        putCalled.incrementAndGet()
                        HttpResponse(200, emptyMap(), ByteArray(0))
                    }
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            }
        }

        saved.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        assertEquals(0, putCalled.get())
        assertEquals("Home Journal", coordinator.currentReading("jid-1", "sha256:ca1").name)
    }

    @Test
    fun putConflictRetriesGetAndResamplesSnapshotOnce() {
        val store = FakeStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val getCount = AtomicInteger(0)
        val putCount = AtomicInteger(0)
        val descCount = AtomicInteger(0)

        val descProvider = {
            val count = descCount.incrementAndGet()
            ClientReportedDescription(name = "Name-$count")
        }

        coordinator.onUsableConnection("jid-1", "sha256:ca1", "sha256:cert1", localDescriptionProvider = descProvider) {
            RoutingFakeClient { method, path, _ ->
                when {
                    method == "GET" && path == "/app/network/api/clients/self" -> {
                        getCount.incrementAndGet()
                        val json = """{"protocol_version":1,"revision":1,"journal":{"name":"J","version":"1.0"},"reported":{"name":"Other","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null}"""
                        HttpResponse(200, emptyMap(), json.toByteArray())
                    }
                    method == "PUT" && path == "/app/network/api/clients/self" -> {
                        val call = putCount.incrementAndGet()
                        if (call == 1) {
                            HttpResponse(409, emptyMap(), ByteArray(0))
                        } else {
                            HttpResponse(200, emptyMap(), """{"protocol_version":1,"revision":2,"reported":{"name":"Name-2","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null,"journal":{"name":"Home Journal","version":"1.2.3"}}""".toByteArray())
                        }
                    }
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            }
        }

        saved.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        assertEquals(2, getCount.get())
        assertEquals(2, putCount.get())
        assertEquals(2, descCount.get())
    }

    @Test
    fun clientsSelf404FallsBackToSystemStatus() {
        val store = FakeStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val statusJson = """{"version":{"current":"0.9.5"}}"""

        coordinator.onUsableConnection("jid-1", "sha256:ca1") {
            RoutingFakeClient { _, path, _ ->
                when {
                    path == "/app/network/api/clients/self" -> HttpResponse(404, emptyMap(), ByteArray(0))
                    path == "/api/system/status" -> HttpResponse(200, emptyMap(), statusJson.toByteArray())
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            }
        }

        saved.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertEquals("0.9.5", reading.version)
        assertNull(reading.name)
        assertEquals(JournalVersionFreshness.CURRENT, reading.freshness)
    }

    @Test
    fun connectionLostDemotesFreshnessWithoutModifyingStore() {
        val store = FakeStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        coordinator.onUsableConnection("jid-1", "sha256:ca1") {
            RoutingFakeClient { _, _, _ ->
                HttpResponse(200, emptyMap(), """{"protocol_version":1,"revision":1,"journal":{"name":"J","version":"1.2.3"},"reported":null,"owner_label":null,"display_label":"Phone","updated_at":null}""".toByteArray())
            }
        }

        saved.await(3, TimeUnit.SECONDS)
        assertEquals(JournalVersionFreshness.CURRENT, coordinator.currentReading("jid-1", "sha256:ca1").freshness)

        coordinator.onConnectionLost()

        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertEquals("1.2.3", reading.version)
        assertEquals("J", reading.name)
        assertEquals(JournalVersionFreshness.LAST_KNOWN, reading.freshness)
        assertEquals(JournalVersionRecord("jid-1", "sha256:ca1", "1.2.3", "J"), store.savedRecord)

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }

    @Test
    fun identityMismatchReadsAsNeverObserved() {
        val store = FakeStore()
        store.save(JournalVersionRecord("jid-1", "sha256:ca1", "1.2.3", "J"))
        val coordinator = JournalVersionRefreshCoordinator(store)

        val mismatchJid = coordinator.currentReading("jid-other", "sha256:ca1")
        assertNull(mismatchJid.version)
        assertEquals(JournalVersionFreshness.NEVER_OBSERVED, mismatchJid.freshness)

        val mismatchCa = coordinator.currentReading("jid-1", "sha256:other")
        assertNull(mismatchCa.version)
        assertEquals(JournalVersionFreshness.NEVER_OBSERVED, mismatchCa.freshness)
    }

    @Test
    fun corruptGetRetainsLastKnown() {
        val store = FakeStore()
        store.save(JournalVersionRecord("jid-1", "sha256:ca1", "1.2.3", "Initial Journal"))
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val latch = CountDownLatch(1)
        coordinator.onUsableConnection("jid-1", "sha256:ca1") {
            latch.countDown()
            RoutingFakeClient { _, _, _ ->
                HttpResponse(200, emptyMap(), "not-valid-json".toByteArray())
            }
        }

        latch.await(3, TimeUnit.SECONDS)
        Thread.sleep(200)

        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertEquals("1.2.3", reading.version)
        assertEquals("Initial Journal", reading.name)
        assertEquals(JournalVersionFreshness.LAST_KNOWN, reading.freshness)

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }

    @Test
    fun timeoutOrErrorRetainsLastKnown() {
        val store = FakeStore()
        store.save(JournalVersionRecord("jid-1", "sha256:ca1", "1.2.3", "Initial Journal"))
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val latch = CountDownLatch(1)
        coordinator.onUsableConnection("jid-1", "sha256:ca1") {
            latch.countDown()
            RoutingFakeClient { _, _, _ ->
                throw java.io.IOException("network reset")
            }
        }

        latch.await(3, TimeUnit.SECONDS)
        Thread.sleep(200)

        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertEquals("1.2.3", reading.version)
        assertEquals("Initial Journal", reading.name)
        assertEquals(JournalVersionFreshness.LAST_KNOWN, reading.freshness)

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }

    @Test
    fun ownerLabelNotSentOnPut() {
        val store = FakeStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val localDesc = ClientReportedDescription(name = "Pixel 8", platform = "android")
        val getJson = """{"protocol_version":1,"revision":1,"journal":{"name":"Home Journal","version":"1.2.3"},"reported":{"name":"Old","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null}""".trimIndent()

        var capturedPutBody: String? = null

        coordinator.onUsableConnection("jid-1", "sha256:ca1", "sha256:cert1", localDescriptionProvider = { localDesc }) {
            RoutingFakeClient { method, path, body ->
                when {
                    method == "GET" && path == "/app/network/api/clients/self" -> HttpResponse(200, emptyMap(), getJson.toByteArray())
                    method == "PUT" && path == "/app/network/api/clients/self" -> {
                        capturedPutBody = body?.toString(Charsets.UTF_8)
                        HttpResponse(200, emptyMap(), """{"protocol_version":1,"revision":2,"reported":{"name":"Pixel 8","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null,"journal":{"name":"Home Journal","version":"1.2.3"}}""".toByteArray())
                    }
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            }
        }

        saved.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        val body = capturedPutBody
        org.junit.Assert.assertNotNull(body)
        org.junit.Assert.assertFalse(body!!.contains("owner_label"))
        org.junit.Assert.assertTrue(body.contains("reported"))
    }

    @Test
    fun omittedProviderIsReadOnlyGetAndCacheWithZeroPuts() {
        val store = FakeStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val getJson = """{"protocol_version":1,"revision":1,"journal":{"name":"Read Only Journal","version":"3.2.1"},"reported":{"name":"Old","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null}""".trimIndent()

        val putCalls = AtomicInteger(0)

        coordinator.onUsableConnection("jid-1", "sha256:ca1", "sha256:cert1", localDescriptionProvider = null) {
            RoutingFakeClient { method, path, _ ->
                when {
                    method == "GET" && path == "/app/network/api/clients/self" -> HttpResponse(200, emptyMap(), getJson.toByteArray())
                    method == "PUT" && path == "/app/network/api/clients/self" -> {
                        putCalls.incrementAndGet()
                        HttpResponse(200, emptyMap(), ByteArray(0))
                    }
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            }
        }

        saved.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        assertEquals(0, putCalls.get())
        assertEquals("3.2.1", coordinator.currentReading("jid-1", "sha256:ca1").version)
        assertEquals("Read Only Journal", coordinator.currentReading("jid-1", "sha256:ca1").name)
        assertEquals(JournalVersionFreshness.CURRENT, coordinator.currentReading("jid-1", "sha256:ca1").freshness)
    }

    @Test
    fun delayedGetAfterPairingMismatchOrGenBumpDoesNotPut() {
        val store = FakeStore()
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val getStarted = CountDownLatch(1)
        val getBlocker = CountDownLatch(1)
        val putCalls = AtomicInteger(0)
        var pairingMatches = true

        val localDesc = ClientReportedDescription(name = "Pixel 8", platform = "android")
        val getJson = """{"protocol_version":1,"revision":1,"journal":{"name":"Home Journal","version":"1.2.3"},"reported":{"name":"Old","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null}""".trimIndent()

        coordinator.onUsableConnection(
            instanceId = "jid-1",
            caChainFingerprint = "sha256:ca1",
            clientCertFingerprint = "sha256:cert1",
            localDescriptionProvider = { localDesc },
            pairingMatches = { pairingMatches },
        ) {
            RoutingFakeClient { method, path, _ ->
                when {
                    method == "GET" && path == "/app/network/api/clients/self" -> {
                        getStarted.countDown()
                        getBlocker.await(3, TimeUnit.SECONDS)
                        HttpResponse(200, emptyMap(), getJson.toByteArray())
                    }
                    method == "PUT" && path == "/app/network/api/clients/self" -> {
                        putCalls.incrementAndGet()
                        HttpResponse(200, emptyMap(), ByteArray(0))
                    }
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            }
        }

        getStarted.await(3, TimeUnit.SECONDS)
        // Pairing changes / invalidates while GET is in flight
        pairingMatches = false
        getBlocker.countDown()

        Thread.sleep(200)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        assertEquals(0, putCalls.get())
        assertNull(store.savedRecord)
    }

    @Test
    fun legacyNotFoundPreservesPreviouslyCachedNameAndUses64k() {
        val store = FakeStore()
        store.save(JournalVersionRecord("jid-1", "sha256:ca1", "1.0.0", "Previously Cached Name"))
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        var clientUsed: RoutingFakeClient? = null

        coordinator.onUsableConnection("jid-1", "sha256:ca1", "sha256:cert1") {
            RoutingFakeClient { method, path, _ ->
                when {
                    method == "GET" && path == "/app/network/api/clients/self" -> HttpResponse(404, emptyMap(), ByteArray(0))
                    method == "GET" && path == "/api/system/status" -> HttpResponse(
                        200,
                        emptyMap(),
                        """{"version":{"current":"2.0.0"}}""".toByteArray(),
                    )
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            }.also { clientUsed = it }
        }

        saved.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        assertEquals(64 * 1024, clientUsed?.lastMaxResponseBytes)
        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertEquals("2.0.0", reading.version)
        assertEquals("Previously Cached Name", reading.name)
        assertEquals(JournalVersionFreshness.CURRENT, reading.freshness)
        assertEquals(JournalVersionRecord("jid-1", "sha256:ca1", "2.0.0", "Previously Cached Name"), store.savedRecord)
    }

    private class FakeMutator(
        var home: PairedHome?,
        var liveEligible: Boolean = true,
    ) : IdentityMutator {
        val mutationGen = AtomicLong(1L)
        var mutateCallback: (() -> Unit)? = null

        override fun current(): PairedHome? = home
        override fun currentPairingGeneration(): PairingGeneration? =
            home?.let { PairingGeneration(it.instanceId, it.clientCertFingerprint) }
        override fun currentAccessMutationGen(): Long = mutationGen.get()
        override fun isRelayLiveEligible(): Boolean = liveEligible
        override fun disableRelayLive() {
            liveEligible = false
        }
        override fun lastPersistenceIssue(): app.solstone.core.identity.PersistenceIssue? = null
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

    private fun createJwt(claimsJson: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString(claimsJson.toByteArray())
        return "$header.$payload.sig"
    }

    private fun awaitUninterruptibly(latch: CountDownLatch, timeout: Long, unit: TimeUnit): Boolean {
        var interrupted = false
        try {
            val deadline = System.nanoTime() + unit.toNanos(timeout)
            var remaining = unit.toNanos(timeout)
            while (true) {
                try {
                    return latch.await(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                    remaining = deadline - System.nanoTime()
                    if (remaining <= 0) return false
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    @Test
    fun accessGenAdvancesUnderSamePairingDoesNotDropPut() {
        val initialHome = PairedHome(
            instanceId = "jid-1",
            homeLabel = "Home",
            relayOrigin = null,
            caChainFingerprint = "sha256:ca1",
            clientCertFingerprint = "sha256:cert1",
            observerHandle = "phone",
            deviceToken = null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        val mutator = FakeMutator(initialHome)
        val store = FakeStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val journalCoordinator = JournalVersionRefreshCoordinator(store, executor)
        val relayCoordinator = RelayAccessRefreshCoordinator(mutator, executor)

        val getStarted = CountDownLatch(1)
        val getBlocker = CountDownLatch(1)
        val putCalls = AtomicInteger(0)
        val relayMutated = CountDownLatch(1)
        mutator.mutateCallback = { relayMutated.countDown() }

        val localDesc = ClientReportedDescription(name = "Pixel 8 New", platform = "android")
        val getJson = """{"protocol_version":1,"revision":1,"journal":{"name":"Home Journal","version":"1.2.3"},"reported":{"name":"Old","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null}""".trimIndent()
        val putJson = """{"protocol_version":1,"revision":2,"reported":{"name":"Pixel 8 New","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null,"journal":{"name":"Home Journal","version":"1.2.3"}}""".trimIndent()

        val expectedPairing = PairingGeneration("jid-1", "sha256:cert1")

        journalCoordinator.onUsableConnection(
            instanceId = "jid-1",
            caChainFingerprint = "sha256:ca1",
            clientCertFingerprint = "sha256:cert1",
            localDescriptionProvider = { localDesc },
            pairingMatches = { mutator.currentPairingGeneration() == expectedPairing },
        ) {
            RoutingFakeClient { method, path, _ ->
                when {
                    method == "GET" && path == "/app/network/api/clients/self" -> {
                        getStarted.countDown()
                        awaitUninterruptibly(getBlocker, 15, TimeUnit.SECONDS)
                        HttpResponse(200, emptyMap(), getJson.toByteArray())
                    }
                    method == "PUT" && path == "/app/network/api/clients/self" -> {
                        putCalls.incrementAndGet()
                        HttpResponse(200, emptyMap(), putJson.toByteArray())
                    }
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            }
        }

        assertTrue(getStarted.await(3, TimeUnit.SECONDS))

        // While GET is blocked, drive RelayAccessRefreshCoordinator with a Ready response under the same pairing
        val exp = System.currentTimeMillis() / 1000L + 86400L
        val jwt = createJwt("""
        {
            "iss": "solstone",
            "ver": 2,
            "aud": "spl-relay",
            "scope": "session.dial",
            "instance_id": "jid-1",
            "sub": "instance:jid-1",
            "iat": 1700000000,
            "exp": $exp,
            "jti": "jti-1"
        }
        """.trimIndent())
        val expiresAt = java.time.Instant.ofEpochSecond(exp).toString()
        val readyJson = """
        {
            "protocol_version": 2,
            "status": "ready",
            "relay_origin": "https://relay.solstone.app",
            "instance_id": "jid-1",
            "device_token": "$jwt",
            "expires_at": "$expiresAt"
        }
        """.trimIndent()

        relayCoordinator.onUsableConnection("jid-1", "sha256:ca1", "sha256:cert1") {
            RoutingFakeClient { _, _, _ ->
                HttpResponse(200, emptyMap(), readyJson.toByteArray())
            }
        }

        assertTrue(relayMutated.await(3, TimeUnit.SECONDS))
        assertEquals(2L, mutator.currentAccessMutationGen())
        assertEquals("https://relay.solstone.app", mutator.home?.relayOrigin)
        assertEquals(jwt, mutator.home?.deviceToken)

        // Unblock GET within the 15s bound
        getBlocker.countDown()

        assertTrue(saved.await(3, TimeUnit.SECONDS))
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        assertEquals(1, putCalls.get())
        assertEquals("Pixel 8 New", localDesc.name)
        assertEquals(JournalVersionRecord("jid-1", "sha256:ca1", "1.2.3", "Home Journal"), store.savedRecord)
    }
}
