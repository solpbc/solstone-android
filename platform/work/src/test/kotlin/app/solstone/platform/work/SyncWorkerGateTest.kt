// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.JournalConfirmation
import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.identity.JournalMarkStore
import app.solstone.core.identity.JournalVersionRecord
import app.solstone.core.identity.JournalVersionStore
import app.solstone.core.identity.ObtainResult
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PushKeyAccess
import app.solstone.core.identity.StoreInspectResult
import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.observer.INGEST_PATH
import app.solstone.core.observer.SEGMENTS_PATH
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.JournalIdentityRefreshCoordinator
import app.solstone.core.pl.JournalVersionRefreshCoordinator
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.RelayAccessRefreshCoordinator
import app.solstone.core.pl.revokeClient
import app.solstone.core.push.DistributorPort
import app.solstone.core.push.DistributorResolution
import app.solstone.core.push.PushRegistrationCoordinator
import app.solstone.core.push.PushRegistrationFile
import app.solstone.core.push.PushRegistrationState
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.wireKeys
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.spool.serializeManifest
import app.solstone.platform.identity.file.FileClientCredentialStore
import app.solstone.platform.identity.file.FileEndpointStore
import app.solstone.platform.identity.file.FileIdentityStore
import app.solstone.platform.identity.file.FileJournalConfirmationStore
import app.solstone.platform.identity.file.FileJournalMarkStore
import app.solstone.platform.identity.file.FileJournalVersionStore
import app.solstone.platform.identity.file.FilePairingGraph
import app.solstone.platform.identity.file.SecretProtector
import app.solstone.platform.persistence.room.ConfirmedCopyFinisher
import app.solstone.platform.persistence.room.EventRow
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncWorkerGateTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val fakeProtector = object : SecretProtector {
        override fun protect(plaintext: ByteArray) = plaintext
        override fun unprotect(wrapped: ByteArray) = wrapped
    }

    private val capturedDiag = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        workerLog = { _, _, _ -> }
        JournalConfirmationPolicy.consults = true
        JournalConfirmationGrandfather.resetForTest()
        SyncDrainGate.resetForTest()
        capturedDiag.clear()
        SyncWorker.syncDiag = { line -> capturedDiag.add(line) }
    }

    @After
    fun tearDown() {
        SyncWorker.syncDiag = null
        workerLog = { _, _, _ -> }
        JournalConfirmationPolicy.consults = true
        JournalConfirmationGrandfather.resetForTest()
        SyncDrainGate.resetForTest()
    }

    private fun testHome(cert: String = "sha256:cert-1", id: String = "home-1") = PairedHome(
        instanceId = id,
        homeLabel = "Home",
        relayOrigin = "https://link.solstone.app",
        caChainFingerprint = "sha256:ca-1",
        clientCertFingerprint = cert,
        observerHandle = "obs",
        deviceToken = "token-1",
        expiresAt = "2030-01-01T00:00:00Z",
        state = IdentityState.PAIRED,
    )

    private fun testCred() = ClientCredential(
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\nkey-1\n-----END PRIVATE KEY-----\n",
        clientCertPem = "-----BEGIN CERTIFICATE-----\ncert-1\n-----END CERTIFICATE-----\n",
        caChainPem = listOf("-----BEGIN CERTIFICATE-----\nca-1\n-----END CERTIFICATE-----\n"),
    )

    private class TestDistributorPort : DistributorPort {
        val registeredVapidKeys = CopyOnWriteArrayList<String>()
        override fun resolveDefault(): DistributorResolution = DistributorResolution.Found("org.fake.distributor")
        override fun available(): List<String> = listOf("org.fake.distributor")
        override val ownPackage: String = "app.solstone.phone"
        override fun save(pkg: String) {}
        override fun register(vapidKey: String) {
            registeredVapidKeys.add(vapidKey)
        }
        override fun unregister() {}
        override fun installedSince(pkg: String): Long? = null
    }

    private fun createStores(
        cert: String = "sha256:cert-1",
        enablePush: Boolean = true,
    ): Triple<SyncStores, FilePairingGraph, TestDistributorPort?> {
        val root = temp.newFolder()
        val identFile = File(root, "identity.tsv")
        val credFile = File(root, "credential.pem")
        val epFile = File(root, "endpoint.txt")
        val commitFile = File(root, "pairing.commit")
        val confirmFile = File(root, "journal_confirmation.json")
        val jvFile = File(root, "journal_version.tsv")
        val jmFile = File(root, "journal_mark.json")
        val pushKeyFile = File(root, "push-key.bin")

        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = fakeProtector,
            pushKeyFile = pushKeyFile,
            pushKeyProtector = fakeProtector,
        )
        val mutator = PublisherIdentityMutatorAdapter(graph)
        val jvStore = FileJournalVersionStore(jvFile)
        val jmStore = FileJournalMarkStore(jmFile)
        val confirmStore = FileJournalConfirmationStore(confirmFile)

        var port: TestDistributorPort? = null
        var pushCoord: PushRegistrationCoordinator? = null
        if (enablePush) {
            val pushDir = File(root, "push").apply { mkdirs() }
            PushRegistrationFile.write(File(pushDir, PushRegistrationFile.FILE_NAME), PushRegistrationState(ownerOn = true)) {}
            val p = TestDistributorPort()
            port = p
            pushCoord = PushRegistrationCoordinator(
                directory = pushDir,
                port = p,
                enabled = true,
                pushKeys = graph,
                pairingNow = { mutator.currentPairingGeneration() },
                log = {},
                enqueue = {},
            )
        }

        val stores = SyncStores(
            publisher = graph,
            pushKeys = graph,
            endpointStore = FileEndpointStore(epFile),
            credentialStore = FileClientCredentialStore(credFile, fakeProtector),
            identityStore = FileIdentityStore(identFile, fakeProtector),
            identityMutator = mutator,
            journalVersionStore = jvStore,
            journalVersionCoordinator = JournalVersionRefreshCoordinator(jvStore),
            relayAccessCoordinator = RelayAccessRefreshCoordinator(mutator),
            journalMarkStore = jmStore,
            journalIdentityCoordinator = JournalIdentityRefreshCoordinator(store = jmStore, publisher = graph),
            journalConfirmationStore = confirmStore,
            pushRegistration = pushCoord,
        )
        return Triple(stores, graph, port)
    }

    private fun setupSpoolAndDrainStore(spoolDir: File): Pair<FakeDrainStore, List<String>> {
        val segmentId1 = "$WORK_TEST_DAY/$MAIN_STREAM/seg-1"
        val segmentDir1 = File(File(File(spoolDir, WORK_TEST_DAY), MAIN_STREAM), "seg-1").apply { mkdirs() }
        File(segmentDir1, "a.bin").writeBytes(byteArrayOf(1, 2, 3))
        val manifest1 = """
            solstone-bundle-manifest-v1
            day=$WORK_TEST_DAY
            segment=seg-1
            startEpochMs=1
            endEpochMs=2
            zoneId=UTC
            utcOffsetSeconds=0
            [files]
            source1	a.bin	sha-a	3	application/octet-stream	1	2
            [gaps]
        """.trimIndent()
        File(segmentDir1, "manifest").writeText(manifest1)

        val day2 = "2026-03-02"
        val segmentId2 = "$day2/$MAIN_STREAM/seg-2"
        val segmentDir2 = File(File(File(spoolDir, day2), MAIN_STREAM), "seg-2").apply { mkdirs() }
        File(segmentDir2, "b.bin").writeBytes(byteArrayOf(4, 5, 6))
        val manifest2 = """
            solstone-bundle-manifest-v1
            day=$day2
            segment=seg-2
            startEpochMs=10
            endEpochMs=20
            zoneId=UTC
            utcOffsetSeconds=0
            [files]
            source2	b.bin	sha-b	3	application/octet-stream	10	20
            [gaps]
        """.trimIndent()
        File(segmentDir2, "manifest").writeText(manifest2)

        val segRow1 = segment(segmentId1).copy(day = WORK_TEST_DAY, stream = MAIN_STREAM, segment = "seg-1", dirSegment = "seg-1", byteSize = 3)
        val fileRow1 = file(segmentId1).copy(sourceId = "source1", name = "a.bin", sha256 = "sha-a", byteSize = 3)
        val segRow2 = segment(segmentId2).copy(day = day2, stream = MAIN_STREAM, segment = "seg-2", dirSegment = "seg-2", byteSize = 3)
        val fileRow2 = file(segmentId2).copy(sourceId = "source2", name = "b.bin", sha256 = "sha-b", byteSize = 3)

        val store = FakeDrainStore(
            segRow1, segRow2,
            files = mapOf(
                segmentId1 to listOf(fileRow1),
                segmentId2 to listOf(fileRow2),
            ),
        )
        return store to listOf(segmentId1, segmentId2)
    }

    private class MockPlHttpClient(
        private val onRequest: (method: String, path: String, body: ByteArray?) -> HttpResponse = { _, _, _ -> HttpResponse(200, emptyMap(), ByteArray(0)) },
    ) : PlHttpClient, Closeable {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse {
            requests.add(RecordedRequest(method, path, headers, body))
            return onRequest(method, path, body)
        }
        override fun close() {}
    }

    private fun standardMockResponses(
        method: String,
        path: String,
        body: ByteArray? = null,
        onPutSelf: (() -> Unit)? = null,
    ): HttpResponse =
        when {
            method == "GET" && path == "/app/network/api/status" ->
                HttpResponse(200, emptyMap(), ByteArray(0))
            method == "GET" && path == "/app/network/api/clients/self" -> {
                val getJson = """{"protocol_version":1,"revision":1,"journal":{"name":"Home Journal","version":"1.2.3"},"reported":{"name":"Old Name","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null}"""
                HttpResponse(200, emptyMap(), getJson.toByteArray())
            }
            method == "PUT" && path == "/app/network/api/clients/self" -> {
                onPutSelf?.invoke()
                val putJson = """{"protocol_version":1,"revision":2,"reported":{"name":"android","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null,"journal":{"name":"Home Journal","version":"1.2.3"}}"""
                HttpResponse(200, emptyMap(), putJson.toByteArray())
            }
            method == "GET" && path.startsWith(SEGMENTS_PATH) ->
                HttpResponse(200, emptyMap(), """{"items":[{"key":"remote","observed":true,"files":[]}],"total":1,"protocol_version":3}""".toByteArray())
            method == "POST" && path == INGEST_PATH -> {
                val bodyText = body?.toString(Charsets.UTF_8) ?: ""
                val name = if (bodyText.contains("b.bin")) "b.bin" else "a.bin"
                val sha = if (bodyText.contains("b.bin")) "sha-b" else "sha-a"
                HttpResponse(200, emptyMap(), """{"status":"ok","segment":"srv-a","file_descriptors":[{"submitted":"$name","written":"$name","size":3,"sha256":"$sha","disposition":"written"}]}""".toByteArray())
            }
            method == "GET" && path == "/api/push/vapid-key" -> {
                val pubKeyBytes = ByteArray(65).apply { this[0] = 4 }
                val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(pubKeyBytes)
                HttpResponse(200, emptyMap(), """{"public_key":"$b64"}""".toByteArray())
            }
            method == "POST" && path == "/api/push/subscriptions" ->
                HttpResponse(200, emptyMap(), """{"status":"ok"}""".toByteArray())
            method == "POST" && path == "/api/push/register" ->
                HttpResponse(200, emptyMap(), """{"status":"ok"}""".toByteArray())
            method == "GET" && path == "/app/network/api/version" ->
                HttpResponse(200, emptyMap(), """{"journal_name":"Test","protocol_version":1,"revision":1,"reported":null,"owner_label":null,"display_label":"Phone","updated_at":null,"journal":{"name":"J","version":"1.0.0"}}""".toByteArray())
            else ->
                HttpResponse(200, emptyMap(), ByteArray(0))
        }

    @Test
    fun scenario1_unconfirmedHeldThenConfirmedSendsAndRegisters() {
        val (stores, graph, port) = createStores(enablePush = true)
        graph.installOrReplace(testHome("sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        val spoolDir = temp.newFolder()
        val (drainStore, segmentIds) = setupSpoolAndDrainStore(spoolDir)

        val putLatch = CountDownLatch(1)
        val client = MockPlHttpClient { method, path, body ->
            standardMockResponses(method, path, body, onPutSelf = { putLatch.countDown() })
        }

        // First run: unconfirmed -> held
        val outcome1 = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> client },
        )

        assertTrue(putLatch.await(5, TimeUnit.SECONDS))
        assertEquals(SyncOutcome.SUCCESS, outcome1)
        assertEquals(listOf("kind=sync outcome=success held=unconfirmed"), capturedDiag)
        for (segId in segmentIds) {
            assertEquals(QueueState.SEALED, drainStore.row(segId).state)
        }
        assertFalse(client.requests.any { it.path.startsWith(SEGMENTS_PATH) })
        assertFalse(client.requests.any { it.path == INGEST_PATH })
        assertFalse(client.requests.any { it.path == "/api/push/register" || it.path == "/api/push/subscriptions" })
        assertFalse(client.requests.any { it.path == "/api/push/vapid-key" })
        assertTrue(port?.registeredVapidKeys?.isEmpty() ?: false)

        capturedDiag.clear()
        client.requests.clear()

        // Confirm pairing
        val confirmed = confirmCurrentJournal(graph, stores.journalConfirmationStore, "sha256:cert-1")
        assertTrue(confirmed)

        // Second run: confirmed -> sends both segments and registers push
        val outcome2 = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> client },
        )

        assertEquals(SyncOutcome.SUCCESS, outcome2)
        assertEquals(listOf("kind=sync outcome=success"), capturedDiag)
        for (segId in segmentIds) {
            assertEquals(QueueState.EVICTED, drainStore.row(segId).state)
        }
        assertTrue(client.requests.any { it.method == "GET" && it.path.startsWith(SEGMENTS_PATH) })
        assertTrue(client.requests.any { it.method == "POST" && it.path == INGEST_PATH })
        val deadline = System.currentTimeMillis() + 3000
        while ((port?.registeredVapidKeys?.isEmpty() ?: true) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(port?.registeredVapidKeys?.isNotEmpty() ?: false)
    }

    @Test
    fun scenario2_inFlightHoldAndConcurrentGateBusy() {
        val (stores, graph, _) = createStores(enablePush = false)
        graph.installOrReplace(testHome("sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        val spoolDir = temp.newFolder()
        val (drainStore, segmentIds) = setupSpoolAndDrainStore(spoolDir)

        val inFlightLatch = CountDownLatch(1)
        val unblockLatch = CountDownLatch(1)

        val blockingClient = MockPlHttpClient { method, path, body ->
            if (path == "/app/network/api/status") {
                inFlightLatch.countDown()
                unblockLatch.await(5, TimeUnit.SECONDS)
            }
            standardMockResponses(method, path, body)
        }

        var firstOutcome: SyncOutcome? = null
        val t = thread {
            firstOutcome = completeSyncRun(
                openStores = { stores },
                spoolDir = spoolDir,
                drainStore = drainStore,
                openClient = { _, _ -> blockingClient },
            )
        }

        assertTrue(inFlightLatch.await(5, TimeUnit.SECONDS))

        // Concurrent sync run while gate is held -> returns RETRY and emits kind=sync outcome=retry
        val concurrentOutcome = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> MockPlHttpClient() },
        )
        assertEquals(SyncOutcome.RETRY, concurrentOutcome)
        assertEquals(listOf("kind=sync outcome=retry"), capturedDiag)

        // Confirm on another thread while run is in-flight
        val confirmed = confirmCurrentJournal(graph, stores.journalConfirmationStore, "sha256:cert-1")
        assertTrue(confirmed)

        // Unblock first run
        unblockLatch.countDown()
        t.join(5000)

        // First run completes with held (snapshot was frozen before confirm)
        assertEquals(SyncOutcome.SUCCESS, firstOutcome)
        assertEquals(listOf("kind=sync outcome=retry", "kind=sync outcome=success held=unconfirmed"), capturedDiag)
        for (segId in segmentIds) {
            assertEquals(QueueState.SEALED, drainStore.row(segId).state)
        }

        // Now test repeat with RETRY (status 503)
        capturedDiag.clear()
        val retryOutcome = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> MockPlHttpClient { _, _, _ -> HttpResponse(503, emptyMap(), ByteArray(0)) } },
        )
        assertEquals(SyncOutcome.RETRY, retryOutcome)
        assertEquals(listOf("kind=sync outcome=retry"), capturedDiag)
        for (segId in segmentIds) {
            assertEquals(QueueState.SEALED, drainStore.row(segId).state)
        }

        // Now test repeat with FAILURE (opener throws fatal error)
        capturedDiag.clear()
        val failureOutcome = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> throw IllegalStateException("fatal opener failure") },
        )
        assertEquals(SyncOutcome.FAILURE, failureOutcome)
        assertEquals(listOf("kind=sync outcome=failure"), capturedDiag)
        for (segId in segmentIds) {
            assertEquals(QueueState.SEALED, drainStore.row(segId).state)
        }

        capturedDiag.clear()

        // Subsequent run uses confirmed credentials and sends both segments
        val subsequentClient = MockPlHttpClient { method, path, body -> standardMockResponses(method, path, body) }
        val nextOutcome = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> subsequentClient },
        )
        assertEquals(SyncOutcome.SUCCESS, nextOutcome)
        assertEquals(listOf("kind=sync outcome=success"), capturedDiag)
        for (segId in segmentIds) {
            assertEquals(QueueState.EVICTED, drainStore.row(segId).state)
        }
    }

    @Test
    fun scenario3_afterCredentialsFrozenSnapshotPreserved() {
        val (stores, graph, _) = createStores(enablePush = false)
        graph.installOrReplace(testHome("sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        val spoolDir = temp.newFolder()
        val (drainStore, segmentIds) = setupSpoolAndDrainStore(spoolDir)

        val client = MockPlHttpClient { method, path, body -> standardMockResponses(method, path, body) }

        val outcome = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> client },
            afterCredentialsFrozen = {
                // Confirm pairing after snapshot was already frozen as unconfirmed
                confirmCurrentJournal(graph, stores.journalConfirmationStore, "sha256:cert-1")
            },
        )

        // Snapshot saw unconfirmed before afterCredentialsFrozen
        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertEquals(listOf("kind=sync outcome=success held=unconfirmed"), capturedDiag)
        for (segId in segmentIds) {
            assertEquals(QueueState.SEALED, drainStore.row(segId).state)
        }
        assertFalse(client.requests.any { it.path == INGEST_PATH })
        assertFalse(client.requests.any { it.path == "/api/push/register" })
    }

    @Test
    fun scenario4_pushDisabledSendsSegmentsWithoutPushRegister() {
        val (stores, graph, port) = createStores(enablePush = false)
        graph.installOrReplace(testHome("sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        confirmCurrentJournal(graph, stores.journalConfirmationStore, "sha256:cert-1")

        val spoolDir = temp.newFolder()
        val (drainStore, segmentIds) = setupSpoolAndDrainStore(spoolDir)

        val client = MockPlHttpClient { method, path, body -> standardMockResponses(method, path, body) }

        val outcome = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> client },
        )

        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertEquals(listOf("kind=sync outcome=success"), capturedDiag)
        for (segId in segmentIds) {
            assertEquals(QueueState.EVICTED, drainStore.row(segId).state)
        }
        assertTrue(port == null || port.registeredVapidKeys.isEmpty())
        assertFalse(client.requests.any { it.path == "/api/push/register" })
        assertFalse(client.requests.any { it.path == "/api/push/vapid-key" })
    }

    @Test
    fun scenario5_unprovenWhileHeldReturnsRetryOrFailureWithoutHeldDiag() {
        val (stores, graph, _) = createStores(enablePush = false)
        graph.installOrReplace(testHome("sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        val spoolDir = temp.newFolder()
        val (drainStore, segmentIds) = setupSpoolAndDrainStore(spoolDir)

        // Status not 200 (503) while held -> returns RETRY
        val failingClient = MockPlHttpClient { _, _, _ -> HttpResponse(503, emptyMap(), ByteArray(0)) }

        val outcomeRetry = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> failingClient },
        )

        assertEquals(SyncOutcome.RETRY, outcomeRetry)
        assertEquals(listOf("kind=sync outcome=retry"), capturedDiag)
        for (segId in segmentIds) {
            assertEquals(QueueState.SEALED, drainStore.row(segId).state)
        }

        capturedDiag.clear()

        // Opener throws non-availability fatal error while held -> returns FAILURE
        val outcomeFailure = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> throw IllegalStateException("fatal opener failure") },
        )

        assertEquals(SyncOutcome.FAILURE, outcomeFailure)
        assertEquals(listOf("kind=sync outcome=failure"), capturedDiag)
        for (segId in segmentIds) {
            assertEquals(QueueState.SEALED, drainStore.row(segId).state)
        }
    }

    @Test
    fun scenario6_legacyGrandfatheredVerbatimFingerprintSends() {
        val cert = "SHA256:UPPERCASE_CERT_123"
        val (stores, graph, _) = createStores(cert = cert, enablePush = false)
        graph.installOrReplace(testHome(cert = cert), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        JournalConfirmationGrandfather.grandfather(stores.publisher, stores.journalConfirmationStore)

        val inspect = stores.journalConfirmationStore.inspect()
        assertTrue(inspect is StoreInspectResult.Ready)
        assertEquals(cert, inspect.value.confirmed)

        val recovered = recoverSyncCredentials(stores.publisher)
        assertTrue(recovered is SyncCredentials.Ready)
        assertEquals(cert, recovered.identity.clientCertFingerprint)

        val spoolDir = temp.newFolder()
        val (drainStore, segmentIds) = setupSpoolAndDrainStore(spoolDir)

        val client = MockPlHttpClient { method, path, body -> standardMockResponses(method, path, body) }

        val outcome = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> client },
        )

        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertEquals(listOf("kind=sync outcome=success"), capturedDiag)
        for (segId in segmentIds) {
            assertEquals(QueueState.EVICTED, drainStore.row(segId).state)
        }
    }

    @Test
    fun scenario7_upgradedPhoneGrandfatherSendsSegments() {
        val (stores, graph, _) = createStores(enablePush = false)
        graph.installOrReplace(testHome("sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        // Upgrade scenario: grandfather runs before sync
        JournalConfirmationGrandfather.grandfather(stores.publisher, stores.journalConfirmationStore)

        val spoolDir = temp.newFolder()
        val (drainStore, segmentIds) = setupSpoolAndDrainStore(spoolDir)

        val client = MockPlHttpClient { method, path, body -> standardMockResponses(method, path, body) }

        val outcome = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> client },
        )

        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertEquals(listOf("kind=sync outcome=success"), capturedDiag)
        for (segId in segmentIds) {
            assertEquals(QueueState.EVICTED, drainStore.row(segId).state)
        }
    }

    @Test
    fun scenario8_heldClientDoesNotContainIdentityOrRevokeAndSeparatelyTestCoordinatorAndRevoke() {
        val (stores, graph, _) = createStores(enablePush = false)
        graph.installOrReplace(testHome("sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        val spoolDir = temp.newFolder()
        val (drainStore, segmentIds) = setupSpoolAndDrainStore(spoolDir)

        val client = MockPlHttpClient { method, path, body -> standardMockResponses(method, path, body) }

        val outcome = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> client },
        )

        assertEquals(SyncOutcome.SUCCESS, outcome)
        // Assert held client does not contain GET /app/network/api/identity or DELETE /app/network/api/clients/
        assertFalse(client.requests.any { it.method == "GET" && it.path == "/app/network/api/identity" })
        assertFalse(client.requests.any { it.method == "DELETE" && it.path.startsWith("/app/network/api/clients/") })
        for (segId in segmentIds) {
            assertEquals(QueueState.SEALED, drainStore.row(segId).state)
        }

        // Separately test JournalIdentityRefreshCoordinator.onUsableConnection does GET /app/network/api/identity
        val identityRequested = CountDownLatch(1)
        val identityClient = MockPlHttpClient { _, path, _ ->
            if (path == "/app/network/api/identity") {
                identityRequested.countDown()
            }
            HttpResponse(200, emptyMap(), """{"protocol_version":1,"instance_id":"home-1","ca_chain_fingerprint":"sha256:ca-1","mark":{"icon1":{"name":"piano","svg":"<svg/>","color_name":"blue","color_hex":"#00f","rot":0},"icon2":{"name":"key","svg":"<svg/>","color_name":"purple","color_hex":"#f0f","rot":0},"words":["w1","w2"]}}""".toByteArray())
        }
        val executor = Executors.newCachedThreadPool()
        val jmCoordinator = JournalIdentityRefreshCoordinator(store = stores.journalMarkStore, executor = executor, publisher = graph)
        jmCoordinator.onUsableConnection("home-1", pairingMatches = { true }) {
            identityClient
        }
        assertTrue(identityRequested.await(5, TimeUnit.SECONDS))
        jmCoordinator.close()
        executor.shutdown()
        assertTrue(identityClient.requests.any { it.method == "GET" && it.path == "/app/network/api/identity" })

        // Separately test revokeClient does DELETE /app/network/api/clients/{cid}
        val validCid = "sha256:" + "a".repeat(64)
        val revokeClientInstance = MockPlHttpClient { _, _, _ -> HttpResponse(200, emptyMap(), ByteArray(0)) }
        revokeClient(revokeClientInstance, validCid)
        assertTrue(revokeClientInstance.requests.any { it.method == "DELETE" && it.path == "/app/network/api/clients/sha256%3A" + "a".repeat(64) })
    }

    @Test
    fun scenario9_afterGrandfatherTestHookCommitsBSyncSendsNothing() {
        val (stores, graph, _) = createStores(cert = "sha256:cert-a", enablePush = false)
        graph.installOrReplace(testHome(cert = "sha256:cert-a", id = "home-A"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        JournalConfirmationGrandfather.grandfather(stores.publisher, stores.journalConfirmationStore)
        val inspectA = stores.journalConfirmationStore.inspect()
        assertTrue(inspectA is StoreInspectResult.Ready)
        assertEquals("sha256:cert-a", inspectA.value.confirmed)

        // Now install new pairing B without confirming B
        graph.installOrReplace(testHome(cert = "sha256:cert-b", id = "home-B"), testCred(), DirectEndpoint("10.0.0.2", 7657), true)

        val spoolDir = temp.newFolder()
        val (drainStore, segmentIds) = setupSpoolAndDrainStore(spoolDir)

        val client = MockPlHttpClient { method, path, body -> standardMockResponses(method, path, body) }

        val outcome = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> client },
        )

        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertEquals(listOf("kind=sync outcome=success held=unconfirmed"), capturedDiag)
        for (segId in segmentIds) {
            assertEquals(QueueState.SEALED, drainStore.row(segId).state)
        }
        assertFalse(client.requests.any { it.path == INGEST_PATH })
    }
}
