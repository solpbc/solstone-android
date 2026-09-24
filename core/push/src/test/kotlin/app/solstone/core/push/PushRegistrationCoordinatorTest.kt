// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.push

import app.solstone.core.identity.ObtainResult
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PushKeyAccess
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.parseJson
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushRegistrationCoordinatorTest {

    private class TestDistributorPort : DistributorPort {
        var resolution: DistributorResolution = DistributorResolution.Found("app.distributor.primary")
        var availableList = mutableListOf("app.distributor.primary")
        override var ownPackage: String = "app.solstone.phone"

        val calls = mutableListOf<String>()
        val savedPackages = mutableListOf<String>()
        val registeredVapids = mutableListOf<String>()

        var onRegister: ((vapidKey: String) -> Unit)? = null
        var onUnregister: (() -> Unit)? = null
        var onAvailable: (() -> Unit)? = null
        var onResolveDefault: (() -> Unit)? = null

        override fun resolveDefault(): DistributorResolution {
            calls += "resolveDefault"
            onResolveDefault?.invoke()
            return resolution
        }

        override fun available(): List<String> {
            calls += "available"
            onAvailable?.invoke()
            return availableList.toList()
        }

        override fun save(pkg: String) {
            calls += "save($pkg)"
            savedPackages += pkg
        }

        override fun register(vapidKey: String) {
            calls += "register($vapidKey)"
            registeredVapids += vapidKey
            onRegister?.invoke(vapidKey)
        }

        override fun unregister() {
            calls += "unregister"
            onUnregister?.invoke()
        }
    }

    private class TestPlHttpClient : PlHttpClient {
        var statusForVapid = 200
        var bodyForVapid: ByteArray? = null

        var statusForRegister = 200
        var bodyForRegister: ByteArray? = ByteArray(0)

        var statusForDelete = 204
        var bodyForDelete: ByteArray? = ByteArray(0)

        val requests = mutableListOf<RequestRecord>()
        var onPath: ((path: String, body: ByteArray?) -> Unit)? = null

        data class RequestRecord(val method: String, val path: String, val bodyText: String?)

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse {
            val bodyText = body?.toString(Charsets.UTF_8)
            requests += RequestRecord(method, path, bodyText)
            onPath?.invoke(path, body)

            return when {
                path == "/api/push/vapid-key" -> {
                    val b = bodyForVapid ?: validVapidJson(sampleVapidKey())
                    HttpResponse(statusForVapid, emptyMap(), b)
                }
                path == "/api/push/register" && method == "POST" -> {
                    HttpResponse(statusForRegister, emptyMap(), bodyForRegister ?: ByteArray(0))
                }
                path == "/api/push/register" && method == "DELETE" -> {
                    HttpResponse(statusForDelete, emptyMap(), bodyForDelete ?: ByteArray(0))
                }
                else -> HttpResponse(404, emptyMap(), ByteArray(0))
            }
        }
    }

    private class TestPushKeys : PushKeyAccess {
        var key: ByteArray? = ByteArray(32) { 0x07 }
        var obtainResult: ObtainResult? = null
        var readThrows = false

        override fun readPushKey(generation: PairingGeneration): ByteArray? {
            if (readThrows) throw IllegalStateException("Key store error")
            return key
        }

        override fun obtainPushKey(generation: PairingGeneration): ObtainResult =
            obtainResult ?: ObtainResult.Obtained(key ?: ByteArray(32) { 0x07 })
    }

    private companion object {
        fun sampleVapidKey(firstByte: Byte = 0x04): ByteArray {
            val k = ByteArray(65) { 0x02 }
            k[0] = firstByte
            return k
        }

        fun validVapidJson(key: ByteArray): ByteArray {
            val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
            return """{"public_key":"$b64"}""".toByteArray(Charsets.UTF_8)
        }

        fun findVectorsFile(): File {
            var current: File? = File(System.getProperty("user.dir"))
            while (current != null) {
                val candidate = File(current, "docs/design/push-envelope-vectors.json")
                if (candidate.exists()) return candidate
                current = current.parentFile
            }
            error("docs/design/push-envelope-vectors.json not found")
        }

        fun sealTestEnvelope(
            key: ByteArray,
            nonce: ByteArray,
            plaintext: ByteArray,
            version: Byte = 0x01.toByte(),
        ): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(byteArrayOf(version))
            val ciphertextAndTag = cipher.doFinal(plaintext)
            return byteArrayOf(version) + nonce + ciphertextAndTag
        }
    }

    @Test
    fun ac02_emptyStateRegistersAndSubsequentPassesPost() {
        val tempDir = Files.createTempDirectory("ac02").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val pushKeys = TestPushKeys()
        val logs = mutableListOf<String>()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var currentGen: PairingGeneration? = gen
        var clockTime = 1000L

        var passLatch = CountDownLatch(1)
        var coordinator: PushRegistrationCoordinator? = null
        coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = pushKeys,
            pairingNow = { currentGen },
            clock = { clockTime },
            log = { logs += it },
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        port.onRegister = { _ ->
            coordinator.onNewEndpoint("https://push.example/ep1", "pubKey1", "auth1")
        }

        // Pass 1
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(listOf("available", "resolveDefault", "unregister", "save(app.distributor.primary)", "register(${Base64.getUrlEncoder().withoutPadding().encodeToString(sampleVapidKey())})"), port.calls)
        assertEquals(listOf("/api/push/vapid-key"), client.requests.map { it.path })

        // Pass 2 (> T later)
        port.calls.clear()
        client.requests.clear()
        clockTime += 70_000L
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        // No new register, only POST; resolveDefault is called when ownerPick is null
        assertEquals(listOf("available", "resolveDefault"), port.calls)
        assertEquals(listOf("/api/push/vapid-key", "/api/push/register"), client.requests.map { it.path })
        val post1 = client.requests.single { it.method == "POST" }
        val parsedPost1 = parseJson(post1.bodyText!!) as Map<*, *>

        // Pass 3 (> T later)
        client.requests.clear()
        clockTime += 70_000L
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        val post2 = client.requests.single { it.method == "POST" }
        val parsedPost2 = parseJson(post2.bodyText!!) as Map<*, *>
        assertEquals(parsedPost1["push_key"], parsedPost2["push_key"])
        assertEquals(PushDeliveryState.Ready, coordinator.deliveryState)
    }

    @Test
    fun ac03_insecureAddressRejectedAndNotQueuedForDelete() {
        val tempDir = Files.createTempDirectory("ac03").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var passLatch = CountDownLatch(1)
        var coordinator: PushRegistrationCoordinator? = null
        coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        port.onRegister = {
            coordinator.onNewEndpoint("http://insecure.example/ep", "pk", "auth")
        }

        // Pass 1: registers and stores endpoint with identity X
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        // Pass 2: endpoint has identity X, proceedToPost -> isAcceptableEndpoint rejects http
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(PushDeliveryState.InsecureAddress, coordinator.deliveryState)

        // Replacement with https does not send DELETE for the insecure http endpoint
        client.requests.clear()
        passLatch = CountDownLatch(1)
        coordinator.onNewEndpoint("https://secure.example/ep", "pk", "auth")
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        assertEquals(0, client.requests.count { it.method == "DELETE" })
    }

    @Test
    fun ac04_vapidFailureVariantsDoNotChangeEndpoint() {
        val tempDir = Files.createTempDirectory("ac04").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var coordinator: PushRegistrationCoordinator? = null
        var passLatch = CountDownLatch(1)
        coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        coordinator.onNewEndpoint("https://push.example/ep", "pk", "auth")

        // 1. NoPush
        client.statusForVapid = 404
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(PushDeliveryState.JournalHasNoPush, coordinator.deliveryState)

        // 2. Invalid
        client.statusForVapid = 200
        client.bodyForVapid = """{"public_key":"short"}""".toByteArray(Charsets.UTF_8)
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(PushDeliveryState.Failed("bad_vapid_key"), coordinator.deliveryState)

        // 3. Failed
        client.statusForVapid = 500
        client.bodyForVapid = "server error".toByteArray(Charsets.UTF_8)
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(PushDeliveryState.Failed("vapid_unavailable"), coordinator.deliveryState)

        // Verify zero registrations
        assertEquals(0, port.registeredVapids.size)
    }

    @Test
    fun ac05_resetWipesCoordinatorAndNextPassUnregistersAndRegisters() {
        val tempDir = Files.createTempDirectory("ac05").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var passLatch = CountDownLatch(1)
        val coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        coordinator.onNewEndpoint("https://push.example/ep1", "pk1", "auth1")
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        coordinator.reset()
        assertEquals(PushDeliveryState.Off, coordinator.deliveryState)
        port.calls.clear()
        client.requests.clear()

        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        assertTrue(port.calls.contains("unregister"))
        assertTrue(port.calls.any { it.startsWith("register(") })
        assertEquals(0, client.requests.count { it.method == "POST" })
    }

    @Test
    fun ac06_newCertificateUnregistersAndDropsOldPendingDeleteUnsent() {
        val tempDir = Files.createTempDirectory("ac06").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen1 = PairingGeneration("inst-1", "sha256:cert1")
        val gen2 = PairingGeneration("inst-1", "sha256:cert2")
        var currentGen = gen1
        var passLatch = CountDownLatch(1)
        var coordinator: PushRegistrationCoordinator? = null
        coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { currentGen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        port.onRegister = {
            coordinator.onNewEndpoint("https://push.example/ep-old", "pk", "auth")
        }

        // Register under gen1
        coordinator.onUsableConnection(gen1, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        // Post under gen1
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen1, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(1, client.requests.count { it.method == "POST" })

        // Switch to gen2
        currentGen = gen2
        port.calls.clear()
        client.requests.clear()
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen2, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        assertTrue(port.calls.contains("unregister"))
        assertTrue(port.calls.any { it.startsWith("register(") })
        // Pending DELETE for old gen1 was dropped unsent
        assertEquals(0, client.requests.count { it.method == "DELETE" })
    }

    @Test
    fun ac07_instanceChangeSendsNoRequestToNewInstanceForOldUrl() {
        val tempDir = Files.createTempDirectory("ac07").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val genA = PairingGeneration("inst-A", "sha256:certA")
        val genB = PairingGeneration("inst-B", "sha256:certB")
        var currentGen = genA
        var passLatch = CountDownLatch(1)
        var coordinator: PushRegistrationCoordinator? = null
        coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { currentGen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        port.onRegister = {
            coordinator.onNewEndpoint("https://push.example/url-A", "pk", "auth")
        }
        coordinator.onUsableConnection(genA, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        currentGen = genB
        client.requests.clear()
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(genB, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        assertFalse(client.requests.any { it.bodyText?.contains("https://push.example/url-A") == true })
    }

    @Test
    fun ac08_attemptForAInvalidatedByPairingMoveToB() {
        val tempDir = Files.createTempDirectory("ac08").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val genA = PairingGeneration("inst-A", "sha256:certA")
        val genB = PairingGeneration("inst-B", "sha256:certB")
        var currentGen = genA
        var passLatch = CountDownLatch(1)
        var coordinator: PushRegistrationCoordinator? = null
        coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { currentGen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        coordinator.onUsableConnection(genA, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        // Change pairing before endpoint arrives
        currentGen = genB
        coordinator.onNewEndpoint("https://push.example/url-orphan", "pk", "auth")

        client.requests.clear()
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(genB, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        assertFalse(client.requests.any { it.bodyText?.contains("https://push.example/url-orphan") == true })
    }

    @Test
    fun ac09_vapidChangeUnregistersBeforeRegisterAndDoesNotPostOldKey() {
        val tempDir = Files.createTempDirectory("ac09").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var passLatch = CountDownLatch(1)
        val coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        val k1 = sampleVapidKey(0x04)
        client.bodyForVapid = validVapidJson(k1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        // Change VAPID to K2
        val k2 = ByteArray(65) { 0x09 }
        k2[0] = 0x04
        client.bodyForVapid = validVapidJson(k2)
        port.calls.clear()

        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        val unregIdx = port.calls.indexOf("unregister")
        val regK2Idx = port.calls.indexOf("register(${Base64.getUrlEncoder().withoutPadding().encodeToString(k2)})")
        assertTrue(unregIdx >= 0 && regK2Idx > unregIdx, "unregister must precede register(K2)")
    }

    @Test
    fun ac10_endpointWithNoOpenAttemptRegistersAfterT() {
        val tempDir = Files.createTempDirectory("ac10").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var clockTime = 1000L
        var passLatch = CountDownLatch(1)
        val coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { clockTime },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        // Arrives without open attempt
        coordinator.onNewEndpoint("https://push.example/no-attempt", "pk1", "auth1")

        clockTime += 70_000L
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        assertTrue(port.calls.any { it.startsWith("register(") })
        assertEquals(0, client.requests.count { it.method == "POST" })
    }

    @Test
    fun ac11_sameUrlAndKeysPostsAndStaysReady() {
        val tempDir = Files.createTempDirectory("ac11").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var passLatch = CountDownLatch(1)
        var coordinator: PushRegistrationCoordinator? = null
        coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        port.onRegister = {
            coordinator.onNewEndpoint("https://push.example/ep", "pk", "auth")
        }
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        // Re-delivery of identical endpoint
        coordinator.onNewEndpoint("https://push.example/ep", "pk", "auth")
        port.calls.clear()
        client.requests.clear()

        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        assertEquals(listOf("available", "resolveDefault"), port.calls)
        assertEquals(1, client.requests.count { it.method == "POST" })
        assertEquals(PushDeliveryState.Ready, coordinator.deliveryState)
    }

    @Test
    fun ac12_distributorLeavesAvailableNotPostedQueuedIfPosted() {
        val tempDir = Files.createTempDirectory("ac12").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var passLatch = CountDownLatch(1)
        var coordinator: PushRegistrationCoordinator? = null
        coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        port.onRegister = {
            coordinator.onNewEndpoint("https://push.example/ep", "pk", "auth")
        }
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        // Post it once
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        // Distributor leaves available
        port.availableList.clear()
        port.resolution = DistributorResolution.NoneAvailable
        client.requests.clear()
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        assertEquals(PushDeliveryState.NoDeliveryApp, coordinator.deliveryState)
        // DELETE was queued and sent
        assertEquals(1, client.requests.count { it.method == "DELETE" })
    }

    @Test
    fun ac13_pacingAndUnansweredRules() {
        val tempDir = Files.createTempDirectory("ac13").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var clockTime = 1000L
        var passLatch = CountDownLatch(1)
        val coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { clockTime },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        // Pass 1: registers at t = 1000
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(1, port.registeredVapids.size)

        // Pass at t = 1000 + 30s: no register, WaitingForDelivery(unanswered = false)
        clockTime += 30_000L
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(1, port.registeredVapids.size)
        assertEquals(PushDeliveryState.WaitingForDelivery("app.distributor.primary", false), coordinator.deliveryState)

        // Pass at t = 1000 + 61s: registers again, WaitingForDelivery(unanswered = true)
        clockTime += 31_000L
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(2, port.registeredVapids.size)
        assertEquals(PushDeliveryState.WaitingForDelivery("app.distributor.primary", true), coordinator.deliveryState)

        // Pass 10s later stays WaitingForDelivery(unanswered = true)
        clockTime += 10_000L
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(2, port.registeredVapids.size)
        assertEquals(PushDeliveryState.WaitingForDelivery("app.distributor.primary", true), coordinator.deliveryState)
    }

    @Test
    fun ac14_unregisteredPacing() {
        val tempDir = Files.createTempDirectory("ac14").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var clockTime = 1000L
        var passLatch = CountDownLatch(1)
        val coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { clockTime },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        coordinator.onUnregistered()
        port.calls.clear()

        // Passes between +5s and +45s do not register
        for (offset in listOf(5_000L, 20_000L, 45_000L)) {
            clockTime = 1000L + offset
            passLatch = CountDownLatch(1)
            coordinator.onUsableConnection(gen, { client })
            assertTrue(passLatch.await(3, TimeUnit.SECONDS))
            assertEquals(0, port.registeredVapids.size)
        }

        // Pass at +61s registers exactly once
        clockTime = 1000L + 61_000L
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(1, port.registeredVapids.size)
    }

    @Test
    fun ac15_registrationFailedSetsFailedAndNextAttemptResetUnanswered() {
        val tempDir = Files.createTempDirectory("ac15").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var clockTime = 1000L
        var enqueueCount = 0
        var passLatch = CountDownLatch(1)
        val coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { clockTime },
            log = {},
            enqueue = { enqueueCount++ },
            afterPass = { passLatch.countDown() },
        )

        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        coordinator.onRegistrationFailed("INTERNAL_ERROR")
        assertEquals(PushDeliveryState.Failed("INTERNAL_ERROR"), coordinator.deliveryState)
        assertEquals(0, enqueueCount)

        // Register after T records unanswered = false
        clockTime += 70_000L
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(PushDeliveryState.WaitingForDelivery("app.distributor.primary", false), coordinator.deliveryState)
    }

    @Test
    fun ac16_pendingDeletesLifecycleAndMaxSendsPerPass() {
        val tempDir = Files.createTempDirectory("ac16").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var clockTime = 1000L

        // Write 5 pending deletes
        val initialPending = (1..5).map { PendingPushDelete("https://push.example/ep-$it", gen, 0) }
        PushRegistrationFile.write(
            File(tempDir, PushRegistrationFile.FILE_NAME),
            PushRegistrationState(pendingDeletes = initialPending),
            {},
        )

        // Make delete fail on first try
        client.statusForDelete = 500

        var passLatch = CountDownLatch(1)
        var coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { clockTime },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        // Next pass sends at most 4 deletes
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(4, client.requests.count { it.method == "DELETE" })

        // Reopen coordinator over same directory
        coordinator.close()
        passLatch = CountDownLatch(1)
        coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { clockTime },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        // 2 more failures -> dropped after 3
        client.requests.clear()
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        client.requests.clear()
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun ac17_endpointArrivingDuringInFlightPost() {
        val tempDir = Files.createTempDirectory("ac17").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var passLatch = CountDownLatch(1)
        val postStarted = CountDownLatch(1)
        val postProceed = CountDownLatch(1)

        var coordinator: PushRegistrationCoordinator? = null
        coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        port.onRegister = {
            coordinator.onNewEndpoint("https://push.example/ep1", "pk1", "auth1")
        }

        // Pass 1: registers and stores ep1 with identity X
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        client.onPath = { path, _ ->
            if (path == "/api/push/register") {
                postStarted.countDown()
                postProceed.await(3, TimeUnit.SECONDS)
            }
        }

        // Pass 2: posts ep1
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(postStarted.await(3, TimeUnit.SECONDS))

        // E2 arrives while POST of E1 is in flight
        coordinator.onNewEndpoint("https://push.example/ep2", "pk2", "auth2")
        postProceed.countDown()
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        // E1 was queued for delete
        client.onPath = null
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        assertTrue(client.requests.any { it.method == "DELETE" && it.bodyText?.contains("https://push.example/ep1") == true })
    }

    @Test
    fun ac17_endpointArrivingDuringDistributorResolution() {
        val tempDir = Files.createTempDirectory("ac17_resolve").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var passLatch = CountDownLatch(1)
        val availableStarted = CountDownLatch(1)
        val availableProceed = CountDownLatch(1)

        var coordinator: PushRegistrationCoordinator? = null
        coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        port.onRegister = {
            coordinator.onNewEndpoint("https://push.example/ep1", "pk1", "auth1")
        }

        // Pass 1: registers ep1
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        // Pass 2: posts ep1 (ep1 has posted = true)
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        // Block inside port.available on Pass 3, with statusForDelete = 500 so it stays queued
        client.statusForDelete = 500
        port.onAvailable = {
            availableStarted.countDown()
            availableProceed.await(3, TimeUnit.SECONDS)
        }

        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(availableStarted.await(3, TimeUnit.SECONDS))

        // Call onNewEndpoint(E2) before port call returns
        coordinator.onNewEndpoint("https://push.example/ep2", "pk2", "auth2")
        availableProceed.countDown()
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        // E2 stays stored endpoint, E1 is queued for delete, E2 is not queued
        val stateFile = File(tempDir, PushRegistrationFile.FILE_NAME)
        val stateAfterPass3 = PushRegistrationFile.read(stateFile) {}
        assertEquals("https://push.example/ep2", stateAfterPass3.endpoint?.url)
        assertTrue(stateAfterPass3.pendingDeletes.any { it.url == "https://push.example/ep1" })
        assertFalse(stateAfterPass3.pendingDeletes.any { it.url == "https://push.example/ep2" })

        // Next pass sends DELETE for E1 and not E2
        port.onAvailable = null
        client.requests.clear()
        client.statusForDelete = 204
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        assertTrue(client.requests.any { it.method == "DELETE" && it.bodyText?.contains("https://push.example/ep1") == true })
        assertFalse(client.requests.any { it.method == "DELETE" && it.bodyText?.contains("https://push.example/ep2") == true })
    }

    @Test
    fun ac17_endpointArrivingDuringInFlightPost_failedStatus() {
        val tempDir = Files.createTempDirectory("ac17_failed").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var passLatch = CountDownLatch(1)
        val postStarted = CountDownLatch(1)
        val postProceed = CountDownLatch(1)

        var coordinator: PushRegistrationCoordinator? = null
        coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        port.onRegister = {
            coordinator.onNewEndpoint("https://push.example/ep1", "pk1", "auth1")
        }

        // Pass 1: registers ep1
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        // Set status to 500 (Failed) and delete status to 500 so delete fails in pass 2
        client.statusForRegister = 500
        client.statusForDelete = 500
        client.onPath = { path, _ ->
            if (path == "/api/push/register") {
                postStarted.countDown()
                postProceed.await(3, TimeUnit.SECONDS)
            }
        }

        // Pass 2: posts ep1
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(postStarted.await(3, TimeUnit.SECONDS))

        // E2 arrives while POST of E1 is in flight
        coordinator.onNewEndpoint("https://push.example/ep2", "pk2", "auth2")
        postProceed.countDown()
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        // E1 is still on the pending-DELETE list afterward
        val stateFile = File(tempDir, PushRegistrationFile.FILE_NAME)
        val state = PushRegistrationFile.read(stateFile) {}
        assertTrue(state.pendingDeletes.any { it.url == "https://push.example/ep1" })

        client.onPath = null
        client.requests.clear()
        client.statusForDelete = 204
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        assertTrue(client.requests.any { it.method == "DELETE" && it.bodyText?.contains("https://push.example/ep1") == true })
    }

    @Test
    fun ac18_pairingChangeOrGenerationFenceDuringVapidReply() {
        val tempDir = Files.createTempDirectory("ac18").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val genA = PairingGeneration("inst-A", "sha256:certA")
        var currentGen: PairingGeneration? = genA
        val vapidStarted = CountDownLatch(1)
        val vapidProceed = CountDownLatch(1)

        var passLatch = CountDownLatch(1)
        val coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { currentGen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            boundMillis = 60_000L,
            afterPass = { passLatch.countDown() },
        )

        client.onPath = { path, _ ->
            if (path == "/api/push/vapid-key") {
                vapidStarted.countDown()
                vapidProceed.await(3, TimeUnit.SECONDS)
            }
        }

        coordinator.onUsableConnection(genA, { client })
        assertTrue(vapidStarted.await(3, TimeUnit.SECONDS))

        // Pairing changes during VAPID reply
        currentGen = PairingGeneration("inst-B", "sha256:certB")
        vapidProceed.countDown()
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        assertEquals(0, port.registeredVapids.size)
        assertEquals(0, port.calls.count { it == "unregister" })

        // Generation fence during VAPID reply
        val vapidStarted2 = CountDownLatch(1)
        val vapidProceed2 = CountDownLatch(1)
        passLatch = CountDownLatch(1)
        client.onPath = { path, _ ->
            if (path == "/api/push/vapid-key") {
                vapidStarted2.countDown()
                vapidProceed2.await(3, TimeUnit.SECONDS)
            }
        }

        coordinator.onUsableConnection(currentGen!!, { client })
        assertTrue(vapidStarted2.await(3, TimeUnit.SECONDS))
        coordinator.fenceJobGeneration()
        vapidProceed2.countDown()
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        assertEquals(0, port.registeredVapids.size)
    }

    @Test
    fun ac19_callbacksUnpairedAndPushEventSerialOrdering() {
        val tempDir = Files.createTempDirectory("ac19").toFile()
        val port = TestDistributorPort()
        var currentGen: PairingGeneration? = null
        val coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { currentGen },
            clock = { 1000L },
            log = {},
            enqueue = {},
        )

        val stateFile = File(tempDir, PushRegistrationFile.FILE_NAME)
        val initialFileState = PushRegistrationFile.read(stateFile) {}

        // While pairingNow() is null, onNewEndpoint, onUnregistered, and onRegistrationFailed leave the state file unchanged
        coordinator.onNewEndpoint("https://push.example/unpaired-ep", "pk", "auth")
        coordinator.onUnregistered()
        coordinator.onRegistrationFailed("ERROR")

        val stateAfterUnpaired = PushRegistrationFile.read(stateFile) {}
        assertEquals(initialFileState, stateAfterUnpaired)

        // Pair device
        currentGen = PairingGeneration("inst-1", "sha256:cert1")
        val handler = PushMessageHandler(TestPushKeys(), { currentGen }, { _, _, _ -> }, {})
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-push-serial").apply { isDaemon = true }
        }
        val serial = PushEventSerial(coordinator, handler, executor)

        val distinctiveUrl = "https://push.example/distinctive-serial-ep-8492"
        serial.onUnregistered()
        serial.onNewEndpoint(distinctiveUrl, "pk-distinct", "auth-distinct")

        // Barrier with executor.submit { }.get()
        executor.submit { }.get(3, TimeUnit.SECONDS)

        val finalState = PushRegistrationFile.read(stateFile) {}
        assertEquals(distinctiveUrl, finalState.endpoint?.url)
        assertEquals("pk-distinct", finalState.endpoint?.p256dh)
        assertEquals("auth-distinct", finalState.endpoint?.auth)
        executor.shutdown()
    }

    @Test
    fun ac20_deliveryStateTransitions() {
        val tempDir = Files.createTempDirectory("ac20").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var passLatch = CountDownLatch(1)
        val coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        // After register -> WaitingForDelivery
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(PushDeliveryState.WaitingForDelivery("app.distributor.primary", false), coordinator.deliveryState)

        // User picked distributor -> WaitingForDelivery(D2, false)
        coordinator.onUserPickedDistributor("app.distributor.secondary")
        assertEquals(PushDeliveryState.WaitingForDelivery("app.distributor.secondary", false), coordinator.deliveryState)

        // reset() -> Off
        coordinator.reset()
        assertEquals(PushDeliveryState.Off, coordinator.deliveryState)
    }

    @Test
    fun ac21_ownerPickLifecycle() {
        val tempDir = Files.createTempDirectory("ac21").toFile()
        val port = TestDistributorPort()
        port.resolution = DistributorResolution.ToSelect
        port.availableList = mutableListOf("app.distributor.custom")
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var passLatch = CountDownLatch(1)
        var coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        coordinator.onUserPickedDistributor("app.distributor.custom")
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(listOf("app.distributor.custom"), port.savedPackages)

        // Pick leaves available and resolution returns Found(other)
        port.availableList = mutableListOf("app.distributor.other")
        port.resolution = DistributorResolution.Found("app.distributor.other")
        port.savedPackages.clear()
        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(listOf("app.distributor.other"), port.savedPackages)
    }

    @Test
    fun ac22_enqueueCallsOnAppropriateCallbacks() {
        val tempDir = Files.createTempDirectory("ac22").toFile()
        val port = TestDistributorPort()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var enqueueCount = 0
        val coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { 1000L },
            log = {},
            enqueue = { enqueueCount++ },
        )

        coordinator.onNewEndpoint("https://push.example/ep", "pk", "auth")
        assertEquals(1, enqueueCount)

        coordinator.onUnregistered()
        assertEquals(2, enqueueCount)

        coordinator.reregister()
        assertEquals(3, enqueueCount)

        coordinator.onUserPickedDistributor("app.pkg")
        assertEquals(4, enqueueCount)

        coordinator.onRegistrationFailed("FAIL")
        assertEquals(4, enqueueCount)
    }

    @Test
    fun ac24_pushMessageHandlerVectorsAndFallbacks() {
        val vectorsFile = findVectorsFile()
        val json = parseJson(vectorsFile.readText()) as Map<*, *>
        val cases = json["cases"] as List<*>
        val okCase = cases.map { it as Map<*, *> }.first { it["expect"] == "ok" }
        val keyStr = okCase["key"] as String
        val envStr = okCase["envelope"] as String
        val plaintextJson = okCase["plaintext_json"] as String
        val expectedPlaintext = parseJson(plaintextJson) as Map<*, *>
        val expectedTitle = expectedPlaintext["title"] as String
        val expectedBody = expectedPlaintext["body"] as String

        val padK = (4 - (keyStr.length % 4)) % 4
        val key = Base64.getUrlDecoder().decode(keyStr + "=".repeat(padK))
        val padE = (4 - (envStr.length % 4)) % 4
        val envelope = Base64.getUrlDecoder().decode(envStr + "=".repeat(padE))

        val gen = PairingGeneration("inst-1", "sha256:cert")
        val pushKeys = TestPushKeys().apply { this.key = key }
        val logs = mutableListOf<String>()
        val posted = mutableListOf<Pair<String, String>>()
        val handler = PushMessageHandler(
            pushKeys = pushKeys,
            pairingNow = { gen },
            notifier = { _, title, body ->
                posted += title to body
            },
            log = { logs += it },
        )

        // 1. Valid vector envelope
        handler.onMessage(envelope, true)
        assertEquals(1, posted.size)
        assertEquals(expectedTitle to expectedBody, posted.last())
        assertEquals(0, logs.size)

        fun assertFallbackAndLog(expectedReasonLog: String, block: () -> Unit) {
            posted.clear()
            logs.clear()
            block()
            assertEquals(1, posted.size)
            assertEquals(PushMessageHandler.FALLBACK_TITLE to PushMessageHandler.FALLBACK_BODY, posted.single())
            assertTrue(logs.contains(expectedReasonLog), "Expected log '$expectedReasonLog' in $logs")
            for (line in logs) {
                assertFalse(line.contains(expectedTitle), "Log should not contain vector title")
                assertFalse(line.contains(expectedBody), "Log should not contain vector body")
            }
        }

        // 2. decrypted == false -> not_decrypted
        assertFallbackAndLog("kind=push reason=not_decrypted") {
            handler.onMessage(envelope, false)
        }

        // 3. readPushKey returns null -> no_key
        assertFallbackAndLog("kind=push reason=no_key") {
            pushKeys.key = null
            handler.onMessage(envelope, true)
            pushKeys.key = key
        }

        // 4. readPushKey throws -> internal
        assertFallbackAndLog("kind=push reason=internal") {
            pushKeys.readThrows = true
            handler.onMessage(envelope, true)
            pushKeys.readThrows = false
        }

        // 5. BadKey: key size not 32 -> bad_envelope
        assertFallbackAndLog("kind=push reason=bad_envelope") {
            pushKeys.key = ByteArray(31) { 0x01 }
            handler.onMessage(envelope, true)
            pushKeys.key = key
        }

        // 6. BadLength: envelope size not 1053 -> bad_envelope
        assertFallbackAndLog("kind=push reason=bad_envelope") {
            handler.onMessage(ByteArray(1052), true)
        }

        // 7. BadVersion: 1053 bytes, first byte not 0x01 -> bad_envelope
        assertFallbackAndLog("kind=push reason=bad_envelope") {
            val badVersionEnv = ByteArray(1053) { 0x00 }
            badVersionEnv[0] = 0x02 // not 0x01
            handler.onMessage(badVersionEnv, true)
        }

        // 8. AuthFailed: 1053-byte version 0x01 envelope that will not decrypt -> auth_failed
        assertFallbackAndLog("kind=push reason=auth_failed") {
            pushKeys.key = ByteArray(32) { 0xFF.toByte() }
            handler.onMessage(envelope, true)
            pushKeys.key = key
        }

        // 9. BadPadding: 1024-byte plaintext is non-zero byte other than 0x80 (e.g. 0x01 repeated) -> bad_envelope
        val nonce = ByteArray(12) { 0x05 }
        val badPaddingPlaintext = ByteArray(1024) { 0x01 }
        val badPaddingEnvelope = sealTestEnvelope(key, nonce, badPaddingPlaintext)
        assertFallbackAndLog("kind=push reason=bad_envelope") {
            handler.onMessage(badPaddingEnvelope, true)
        }

        // 10. BadPlaintext: 1024-byte plaintext is "{" followed by 0x80 and zeros -> bad_plaintext
        val badPlaintext = ByteArray(1024) { 0x00 }
        badPlaintext[0] = '{'.code.toByte()
        badPlaintext[1] = 0x80.toByte()
        val badPlaintextEnvelope = sealTestEnvelope(key, nonce, badPlaintext)
        assertFallbackAndLog("kind=push reason=bad_plaintext") {
            handler.onMessage(badPlaintextEnvelope, true)
        }
    }

    @Test
    fun ac25_notificationIdDrawExcludes100To199() {
        val draws = mutableListOf(100, 199, 150, 50)
        val drawnId = nextNotificationId { draws.removeAt(0) }
        assertEquals(50, drawnId)

        // Two onMessage calls with draw sequence yielding two IDs outside 100..199
        val gen = PairingGeneration("inst-1", "sha256:cert")
        val handlerDraws = mutableListOf(120, 42, 180, 84)
        val postedIds = mutableListOf<Int>()
        val handler = PushMessageHandler(
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            notifier = { id, _, _ -> postedIds += id },
            log = {},
            drawId = { handlerDraws.removeAt(0) },
        )

        handler.onMessage(ByteArray(0), false)
        handler.onMessage(ByteArray(0), false)

        assertEquals(2, postedIds.size)
        assertEquals(42, postedIds[0])
        assertEquals(84, postedIds[1])
        assertFalse(postedIds[0] in 100..199)
        assertFalse(postedIds[1] in 100..199)
        assertTrue(postedIds[0] != postedIds[1])
    }

    @Test
    fun ac27_logsNeverContainSensitiveData() {
        val tempDir = Files.createTempDirectory("ac27").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        val logs = mutableListOf<String>()
        val sensitiveUrl = "https://push.example/fixture-endpoint-9f3a"
        val sensitiveKey = ByteArray(32) { 0xDE.toByte() }

        val pushKeys = TestPushKeys().apply { key = sensitiveKey }
        var passLatch = CountDownLatch(1)
        val coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = pushKeys,
            pairingNow = { gen },
            clock = { 1000L },
            log = { logs += it },
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        client.statusForRegister = 403 // NotLinked
        port.onRegister = { coordinator.onNewEndpoint(sensitiveUrl, "pk", "auth") }

        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        passLatch = CountDownLatch(1)
        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        assertTrue(logs.contains("kind=push reason=not_linked"))
        for (logLine in logs) {
            assertFalse(logLine.contains("fixture-endpoint-9f3a"))
            assertFalse(logLine.contains(Base64.getUrlEncoder().withoutPadding().encodeToString(sensitiveKey)))
        }
    }

    @Test
    fun ac28_disabledCoordinatorMakesZeroCallsAndIsOff() {
        val tempDir = Files.createTempDirectory("ac28").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        var passLatch = CountDownLatch(1)
        val coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = false,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        assertEquals(PushDeliveryState.Off, coordinator.deliveryState)

        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))
        assertEquals(PushDeliveryState.Off, coordinator.deliveryState)

        coordinator.reregister()
        coordinator.onUserPickedDistributor("app.pkg")
        coordinator.reset()

        assertEquals(0, client.requests.size)
        assertEquals(0, port.calls.size)
    }

    @Test
    fun ac29_registerReceives87CharVapidKey() {
        val tempDir = Files.createTempDirectory("ac29").toFile()
        val port = TestDistributorPort()
        val client = TestPlHttpClient()
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        val rawKey = ByteArray(65) { 0x05 }
        rawKey[0] = 0x04
        client.bodyForVapid = validVapidJson(rawKey)

        var passLatch = CountDownLatch(1)
        val coordinator = PushRegistrationCoordinator(
            directory = tempDir,
            port = port,
            enabled = true,
            pushKeys = TestPushKeys(),
            pairingNow = { gen },
            clock = { 1000L },
            log = {},
            enqueue = {},
            afterPass = { passLatch.countDown() },
        )

        coordinator.onUsableConnection(gen, { client })
        assertTrue(passLatch.await(3, TimeUnit.SECONDS))

        val regVapid = port.registeredVapids.single()
        assertEquals(87, regVapid.length)
        assertTrue(Regex("^[A-Za-z0-9_-]{87}$").matches(regVapid))
    }

    @Test
    fun corruptFileLogsStateUnreadableAndDoesNotThrow() {
        val tempDir = Files.createTempDirectory("corrupt-test").toFile()
        val stateFile = File(tempDir, PushRegistrationFile.FILE_NAME)
        stateFile.writeText("{invalid-json")
        val logs = mutableListOf<String>()

        val state = PushRegistrationFile.read(stateFile) { logs += it }
        assertEquals(PushRegistrationState.Empty, state)
        assertTrue(logs.contains("kind=push reason=state_unreadable"))
    }
}
