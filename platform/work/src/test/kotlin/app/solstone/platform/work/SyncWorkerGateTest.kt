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
import app.solstone.core.pl.ClientReportedDescription
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
import app.solstone.platform.persistence.room.ImportChange
import app.solstone.platform.persistence.room.ImportCustodyStore
import app.solstone.platform.persistence.room.ImportDao
import app.solstone.platform.persistence.room.ImportDeliveryRow
import app.solstone.platform.persistence.room.ImportItemRow
import app.solstone.platform.persistence.room.ImportRecentReceiptRow
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SourceIntakeRow
import app.solstone.platform.persistence.room.SyncStateRow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
        check(SyncDrainGate.tryAcquire()) { "gate was not free" }
        SyncDrainGate.release()
        capturedDiag.clear()
        SyncWorker.syncDiag = { line -> capturedDiag.add(line) }
    }

    @After
    fun tearDown() {
        SyncWorker.syncDiag = null
        workerLog = { _, _, _ -> }
        JournalConfirmationPolicy.consults = true
        JournalConfirmationGrandfather.resetForTest()
        check(SyncDrainGate.tryAcquire()) { "gate was not free" }
        SyncDrainGate.release()
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
            method == "GET" && path == "/api/system/status" ->
                HttpResponse(200, emptyMap(), """{"capabilities":[]}""".toByteArray())
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
    fun gateBusyReturnsRetryWithoutSendClaim() {
        val (stores, graph, _) = createStores(enablePush = false)
        graph.installOrReplace(testHome("sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        val spoolDir = temp.newFolder()
        val (drainStore, segmentIds) = setupSpoolAndDrainStore(spoolDir)

        assertTrue(SyncDrainGate.tryAcquire())
        try {
            val outcome = completeSyncRun(
                openStores = { stores },
                spoolDir = spoolDir,
                drainStore = drainStore,
                openClient = { _, _ -> MockPlHttpClient() },
                localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
            )
            assertEquals(SyncOutcome.RETRY, outcome)
            assertEquals(listOf("kind=sync outcome=retry"), capturedDiag)
            for (segId in segmentIds) {
                assertEquals(QueueState.SEALED, drainStore.row(segId).state)
            }
        } finally {
            SyncDrainGate.release()
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
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
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
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
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
    fun retryReconcilesAnUploadWhoseAcknowledgmentTimedOutAndDeliversTheRest() {
        val (stores, graph, _) = createStores(enablePush = false)
        graph.installOrReplace(testHome(), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        confirmCurrentJournal(graph, stores.journalConfirmationStore, "sha256:cert-1")

        val spoolDir = temp.newFolder()
        val (drainStore, segmentIds) = setupSpoolAndDrainStore(spoolDir)
        val firstId = segmentIds.first()
        val secondId = segmentIds.last()
        val firstBytes = byteArrayOf(1, 2, 3)
        val firstDigest = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"
        drainStore.add(
            drainStore.row(firstId).copy(sealedAt = 0),
            drainStore.filesBySegmentId(firstId).map { it.copy(sha256 = firstDigest) },
        )

        fun run(client: MockPlHttpClient) = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> client },
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
        )

        var journalHasFirst = false
        val stalled = MockPlHttpClient { method, path, body ->
            when {
                journalHasFirst -> throw SocketTimeoutException("test transport timeout")
                method == "POST" && path == INGEST_PATH -> {
                    assertTrue(body!!.decodeToString().contains("a.bin"))
                    assertTrue(body.asList().windowed(firstBytes.size).contains(firstBytes.toList()))
                    journalHasFirst = true
                    throw SocketTimeoutException("test lost acknowledgment")
                }
                else -> standardMockResponses(method, path, body)
            }
        }
        assertEquals(SyncOutcome.RETRY, run(stalled))
        assertTrue(journalHasFirst)
        assertEquals(QueueState.FAILED, drainStore.row(firstId).state)
        assertEquals(QueueState.SEALED, drainStore.row(secondId).state)
        assertContentEquals(firstBytes, File(spoolDir, "$firstId/a.bin").readBytes())

        // Advance past the retry delay without sleeping. The journal proves the first copy by its
        // segment key, filename, size and hash, so only the second item needs to go over the wire.
        drainStore.add(drainStore.row(firstId).copy(lastAttemptAt = 0))
        val recovered = MockPlHttpClient { method, path, body ->
            if (method == "GET" && path == "$SEGMENTS_PATH/$WORK_TEST_DAY?source=source1") {
                val inventory = """{"items":[{"key":"seg-1","files":[{"name":"a.bin","size":3,"sha256":"$firstDigest","status":"present"}]}],"total":1,"protocol_version":3}"""
                HttpResponse(200, emptyMap(), inventory.toByteArray())
            } else {
                standardMockResponses(method, path, body)
            }
        }
        assertEquals(SyncOutcome.SUCCESS, run(recovered))
        val delivered = recovered.requests.single { it.method == "POST" && it.path == INGEST_PATH }
        assertTrue(delivered.body!!.decodeToString().contains("b.bin"))
        for (id in segmentIds) assertEquals(QueueState.EVICTED, drainStore.row(id).state)
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
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
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
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
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
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
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
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
        )

        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertEquals(listOf("kind=sync outcome=success"), capturedDiag)
        for (segId in segmentIds) {
            assertEquals(QueueState.EVICTED, drainStore.row(segId).state)
        }
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
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
        )

        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertEquals(listOf("kind=sync outcome=success held=unconfirmed"), capturedDiag)
        for (segId in segmentIds) {
            assertEquals(QueueState.SEALED, drainStore.row(segId).state)
        }
        assertFalse(client.requests.any { it.path == INGEST_PATH })
    }

    @Test
    fun importOnlyRetrySchedulesShortDelayedRetryAndMarksScheduled() {
        val (stores, graph, _) = createStores(enablePush = false)
        graph.installOrReplace(testHome("sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        confirmCurrentJournal(graph, stores.journalConfirmationStore, "sha256:cert-1")
        stores.journalVersionStore.save(JournalVersionRecord("home-1", "sha256:ca-1", "2.0.99"))

        val importRoot = temp.newFolder().toPath()
        val importDao = InMemoryImportDao()
        var nowMs = 1_000_000L
        val importStore = ImportCustodyStore(root = importRoot, dao = importDao, clock = { nowMs })
        val id = importStore.begin("file")!!
        val bytes = "import-body".toByteArray()
        Files.write(importRoot.resolve(".staging").resolve(id).resolve("payload"), bytes)
        assertTrue(importStore.publish(id, "doc.txt", "text/plain", bytes.size.toLong()))

        val scheduled = mutableListOf<Pair<Long, Boolean>>()
        val busyClient = MockPlHttpClient { method, path, body ->
            when {
                method == "POST" && path == "/app/import/api/save" ->
                    HttpResponse(503, emptyMap(), """{"error":"busy"}""".toByteArray())
                path == "/app/network/api/clients/self" ->
                    HttpResponse(200, emptyMap(), """{"protocol_version":1,"revision":1,"journal":{"name":"Home Journal","version":"2.0.99"},"reported":{"name":"Phone","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null}""".toByteArray())
                else -> standardMockResponses(method, path, body)
            }
        }

        // Attempt 1: 15s backoff, importRetryScheduled=true, diagnostic still records retry.
        val first = completeSyncRunDetailed(
            openStores = { stores },
            spoolDir = temp.newFolder(),
            drainStore = FakeDrainStore(),
            openClient = { _, _ -> busyClient },
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
            customImportStore = importStore,
            now = { nowMs },
            scheduleImportRetry = { delayMs, allowAppend ->
                scheduled += delayMs to allowAppend
                true
            },
        )
        assertEquals(SyncOutcome.RETRY, first.outcome)
        assertTrue(first.importRetryScheduled)
        assertEquals(listOf(15_000L to true), scheduled)
        assertTrue(capturedDiag.contains("kind=sync outcome=retry"))
        assertTrue(capturedDiag.any { it.contains("kind=import id=$id step=save outcome=retry cause=http-503") })

        // Attempt 2 at now + 15s: 30s backoff.
        nowMs += 15_000L
        val second = completeSyncRunDetailed(
            openStores = { stores },
            spoolDir = temp.newFolder(),
            drainStore = FakeDrainStore(),
            openClient = { _, _ -> busyClient },
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
            customImportStore = importStore,
            now = { nowMs },
            scheduleImportRetry = { delayMs, allowAppend ->
                scheduled += delayMs to allowAppend
                true
            },
        )
        assertEquals(SyncOutcome.RETRY, second.outcome)
        assertTrue(second.importRetryScheduled)
        assertEquals(listOf(15_000L to true, 30_000L to true), scheduled)

        // Auth stop on attempt 3 schedules nothing and returns FAILURE.
        nowMs += 30_000L
        val authClient = MockPlHttpClient { method, path, body ->
            when {
                method == "POST" && path == "/app/import/api/save" ->
                    HttpResponse(401, emptyMap(), """{"error":"unauthorized"}""".toByteArray())
                path == "/app/network/api/clients/self" ->
                    HttpResponse(200, emptyMap(), """{"protocol_version":1,"revision":1,"journal":{"name":"Home Journal","version":"2.0.99"},"reported":{"name":"Phone","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null}""".toByteArray())
                else -> standardMockResponses(method, path, body)
            }
        }
        val third = completeSyncRunDetailed(
            openStores = { stores },
            spoolDir = temp.newFolder(),
            drainStore = FakeDrainStore(),
            openClient = { _, _ -> authClient },
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
            customImportStore = importStore,
            now = { nowMs },
            scheduleImportRetry = { delayMs, allowAppend ->
                scheduled += delayMs to allowAppend
                true
            },
        )
        assertEquals(SyncOutcome.FAILURE, third.outcome)
        assertFalse(third.importRetryScheduled)
        assertEquals(2, scheduled.size)
    }

    @Test
    fun mixedSegmentAndImportRetryKeepsWorkerRetryAndDisallowsAppend() {
        val (stores, graph, _) = createStores(enablePush = false)
        graph.installOrReplace(testHome("sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        confirmCurrentJournal(graph, stores.journalConfirmationStore, "sha256:cert-1")
        stores.journalVersionStore.save(JournalVersionRecord("home-1", "sha256:ca-1", "2.0.99"))

        val spoolDir = temp.newFolder()
        val (drainStore, _) = setupSpoolAndDrainStore(spoolDir)

        val importRoot = temp.newFolder().toPath()
        val importDao = InMemoryImportDao()
        val nowMs = 2_000_000L
        val importStore = ImportCustodyStore(root = importRoot, dao = importDao, clock = { nowMs })
        val id = importStore.begin("file")!!
        val bytes = "mixed-body".toByteArray()
        Files.write(importRoot.resolve(".staging").resolve(id).resolve("payload"), bytes)
        assertTrue(importStore.publish(id, "doc.txt", "text/plain", bytes.size.toLong()))

        val scheduled = mutableListOf<Pair<Long, Boolean>>()
        val busyClient = MockPlHttpClient { method, path, body ->
            when {
                (method == "POST" && path == "/app/import/api/save") || (method == "POST" && path == INGEST_PATH) ->
                    HttpResponse(503, emptyMap(), """{"error":"busy"}""".toByteArray())
                path == "/app/network/api/clients/self" ->
                    HttpResponse(200, emptyMap(), """{"protocol_version":1,"revision":1,"journal":{"name":"Home Journal","version":"2.0.99"},"reported":{"name":"Phone","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null}""".toByteArray())
                else -> standardMockResponses(method, path, body)
            }
        }

        val result = completeSyncRunDetailed(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> busyClient },
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
            customImportStore = importStore,
            now = { nowMs },
            scheduleImportRetry = { delayMs, allowAppend ->
                scheduled += delayMs to allowAppend
                true
            },
        )
        assertEquals(SyncOutcome.RETRY, result.outcome)
        assertFalse(result.importRetryScheduled, "segment failure must still propagate Result.retry()")
        assertEquals(listOf(15_000L to false), scheduled)
    }

    @Test
    fun resumableImportBudgetYieldReturnsSuccessAndSchedulesFollowUp() {
        val (stores, graph, _) = createStores(enablePush = false)
        graph.installOrReplace(testHome("sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        confirmCurrentJournal(graph, stores.journalConfirmationStore, "sha256:cert-1")
        stores.journalVersionStore.save(JournalVersionRecord("home-1", "sha256:ca-1", "2.1.0"))

        val importRoot = temp.newFolder().toPath()
        val importDao = InMemoryImportDao()
        var nowMs = 3_000_000L
        val importStore = ImportCustodyStore(root = importRoot, dao = importDao, clock = { nowMs })
        val id = importStore.begin("file")!!
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        Files.write(importRoot.resolve(".staging").resolve(id).resolve("payload"), bytes)
        assertTrue(importStore.publish(id, "big.bin", "application/octet-stream", bytes.size.toLong()))

        var followUpCount = 0
        val client = MockPlHttpClient { method, path, body ->
            when {
                method == "GET" && path == "/api/system/status" ->
                    HttpResponse(200, emptyMap(), """{"capabilities":["resumable_import_uploads"]}""".toByteArray())
                method == "GET" && path == "/app/import/api/sources" ->
                    HttpResponse(200, emptyMap(), """{"sources":[],"resumable":{"threshold_bytes":512,"chunk_bytes":400,"max_file_bytes":100000}}""".toByteArray())
                method == "POST" && path == "/app/import/api/uploads" ->
                    HttpResponse(201, emptyMap(), """{"status":"open","upload_id":"upl_budget","next_offset":0,"total_bytes":1024,"chunk_bytes":400}""".toByteArray())
                method == "PUT" && path == "/app/import/api/uploads/upl_budget?offset=0" -> {
                    // Advance fake clock past the worker budget after the first chunk succeeds.
                    nowMs += 10_000L
                    HttpResponse(200, emptyMap(), """{"upload_id":"upl_budget","next_offset":400,"total_bytes":1024,"complete":false}""".toByteArray())
                }
                path == "/app/network/api/clients/self" ->
                    HttpResponse(200, emptyMap(), """{"protocol_version":1,"revision":1,"journal":{"name":"Home Journal","version":"2.1.0"},"reported":{"name":"Phone","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null}""".toByteArray())
                else -> standardMockResponses(method, path, body)
            }
        }

        val result = completeSyncRunDetailed(
            openStores = { stores },
            spoolDir = temp.newFolder(),
            drainStore = FakeDrainStore(),
            openClient = { _, _ -> client },
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
            customImportStore = importStore,
            now = { nowMs },
            importTimeBudgetMs = 5_000L,
            scheduleFollowUp = { followUpCount++ },
        )
        assertEquals(SyncOutcome.SUCCESS, result.outcome)
        assertFalse(result.importRetryScheduled)
        assertEquals(1, followUpCount)
        assertEquals(400L, importStore.item(id)!!.uploadOffset)
        assertEquals("paused", importStore.items("home-1", "2.1.0").single().uploadPhase)
    }

    @Test
    fun inFlightResumableImportDoesNotTriggerFollowUpLoop() {
        val (stores, graph, _) = createStores(enablePush = false)
        graph.installOrReplace(testHome("sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        confirmCurrentJournal(graph, stores.journalConfirmationStore, "sha256:cert-1")
        stores.journalVersionStore.save(JournalVersionRecord("home-1", "sha256:ca-1", "2.1.0"))

        val importRoot = temp.newFolder().toPath()
        val importDao = InMemoryImportDao()
        val uidtStore = ImportCustodyStore(root = importRoot, dao = importDao, clock = { 3_000_000L })
        val workerStore = ImportCustodyStore(root = importRoot, dao = importDao, clock = { 3_000_000L })
        val id = uidtStore.begin("file")!!
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        Files.write(importRoot.resolve(".staging").resolve(id).resolve("payload"), bytes)
        assertTrue(uidtStore.publish(id, "big.bin", "application/octet-stream", bytes.size.toLong()))

        // Simulate API 34+ UIDT ImportUploadJobService actively streaming this item.
        assertEquals(ImportChange.DONE, uidtStore.beginExchange(id, "home-1", "2.1.0"))
        try {
            var followUpCount = 0
            val client = MockPlHttpClient { method, path, body ->
                standardMockResponses(method, path, body)
            }
            val result = completeSyncRunDetailed(
                openStores = { stores },
                spoolDir = temp.newFolder(),
                drainStore = FakeDrainStore(),
                openClient = { _, _ -> client },
                localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
                customImportStore = workerStore,
                now = { 3_000_000L },
                scheduleFollowUp = { followUpCount++ },
            )
            assertEquals(SyncOutcome.SUCCESS, result.outcome)
            assertFalse(result.importRetryScheduled)
            assertEquals(0, followUpCount, "concurrent SyncWorker must not spin follow-ups for an in-flight UIDT item")
            assertTrue(client.requests.none { it.path.startsWith("/app/import/api/") })
        } finally {
            uidtStore.endExchange(id)
        }
    }

    private class InMemoryImportDao : ImportDao {
        val rows = java.util.concurrent.ConcurrentHashMap<String, ImportItemRow>()
        val recent = java.util.concurrent.ConcurrentHashMap<String, ImportRecentReceiptRow>()
        var delivery: ImportDeliveryRow? = null
        override fun insert(row: ImportItemRow) { rows[row.id] = row }
        override fun update(row: ImportItemRow): Int = if (rows.containsKey(row.id)) { rows[row.id] = row; 1 } else 0
        override fun delete(id: String): Int = if (rows.remove(id) != null) 1 else 0
        override fun byId(id: String): ImportItemRow? = rows[id]
        override fun all(): List<ImportItemRow> = rows.values.sortedWith(compareBy<ImportItemRow> { it.createdAt }.thenBy { it.id })
        override fun putDelivery(row: ImportDeliveryRow) { delivery = row }
        override fun delivery(): ImportDeliveryRow? = delivery
        override fun putRecentReceipt(row: ImportRecentReceiptRow) { recent[row.id] = row }
        override fun recentReceipts(instanceId: String, cutoff: Long, limit: Int): List<ImportRecentReceiptRow> = emptyList()
        override fun pruneExpiredReceipts(cutoff: Long): Int = 0
        override fun trimRecentReceipts(instanceId: String, keep: Int): Int = 0
        override fun intakeBySource(sourceId: String): SourceIntakeRow? = null
        override fun upsertIntake(row: SourceIntakeRow) = Unit
    }
}
