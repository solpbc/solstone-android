// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.JournalConfirmation
import app.solstone.core.identity.JournalConfirmationStore
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.StoreInspectResult
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.model.QueueState
import app.solstone.core.observer.INGEST_PATH
import app.solstone.core.observer.SEGMENTS_PATH
import app.solstone.core.pl.ClientReportedDescription
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.JournalIdentityRefreshCoordinator
import app.solstone.core.pl.JournalVersionRefreshCoordinator
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.RelayAccessRefreshCoordinator
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.identity.file.FileClientCredentialStore
import app.solstone.platform.identity.file.FileEndpointStore
import app.solstone.platform.identity.file.FileIdentityStore
import app.solstone.platform.identity.file.FileJournalConfirmationStore
import app.solstone.platform.identity.file.FileJournalMarkStore
import app.solstone.platform.identity.file.FileJournalVersionStore
import app.solstone.platform.identity.file.FilePairingGraph
import app.solstone.platform.identity.file.SecretProtector
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class WorkManagerSyncConfirmationRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var rootDir: File
    private lateinit var spoolDir: File
    private lateinit var stores: SyncStores
    private lateinit var graph: FilePairingGraph
    private lateinit var drainStore: FakeDrainStore
    private lateinit var segmentIds: List<String>
    private lateinit var recordingClient: TestRecordingHttpClient

    private lateinit var inFlightLatch: CountDownLatch
    private lateinit var releaseInFlightLatch: CountDownLatch
    private val failFirstStatus = AtomicBoolean(false)
    private val retryFirstStatus = AtomicBoolean(false)

    private val testHome = PairedHome(
        instanceId = "inst-runtime-test",
        homeLabel = "Home",
        relayOrigin = null,
        caChainFingerprint = "sha256:ca-1",
        clientCertFingerprint = "sha256:cert-1",
        observerHandle = "obs",
        deviceToken = null,
        expiresAt = null,
        state = IdentityState.PAIRED,
    )

    private val testCred = ClientCredential(
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----\n",
        clientCertPem = "-----BEGIN CERTIFICATE-----\ncert\n-----END CERTIFICATE-----\n",
        caChainPem = listOf("-----BEGIN CERTIFICATE-----\nca\n-----END CERTIFICATE-----\n"),
    )

    @Before
    fun setUp() {
        inFlightLatch = CountDownLatch(1)
        releaseInFlightLatch = CountDownLatch(1)
        failFirstStatus.set(false)
        retryFirstStatus.set(false)

        rootDir = File(context.cacheDir, "work-runtime-test-${System.currentTimeMillis()}").apply { mkdirs() }
        spoolDir = File(rootDir, "spool").apply { mkdirs() }
        val (setupStores, setupGraph) = createTestStores(rootDir)
        stores = setupStores
        graph = setupGraph
        graph.installOrReplace(testHome, testCred, DirectEndpoint("127.0.0.1", 7657), true)

        val (setupDrain, setupSegIds) = setupSpoolAndDrainStore(spoolDir)
        drainStore = setupDrain
        segmentIds = setupSegIds

        recordingClient = TestRecordingHttpClient { method, path, body ->
            if (path == "/app/network/api/status") {
                if (failFirstStatus.compareAndSet(true, false)) {
                    throw IllegalStateException("fatal failure scripted")
                }
                if (retryFirstStatus.compareAndSet(true, false)) {
                    throw IOException("retry scripted")
                }
            }
            standardMockResponses(method, path, body)
        }

        val workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? {
                if (workerClassName == SyncWorker::class.java.name) {
                    return SyncWorker(
                        context = appContext,
                        params = workerParameters,
                        openStores = { stores },
                        openClient = { _, _ -> recordingClient },
                        drainStore = drainStore,
                        spoolDir = spoolDir,
                        afterCredentialsFrozen = {
                            inFlightLatch.countDown()
                            releaseInFlightLatch.await(10, TimeUnit.SECONDS)
                        },
                        localDescriptionProvider = {
                            ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone")
                        },
                    )
                }
                return null
            }
        }
        currentFactory = { workerFactory }
        ensureWorkManagerInitialized(context)
    }

    @After
    fun tearDown() {
        releaseInFlightLatch.countDown()
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork("solstone-sync-now")
            WorkManager.getInstance(context).cancelUniqueWork("solstone-sync-after-confirm")
            WorkManager.getInstance(context).cancelUniqueWork("solstone-sync-periodic")
        }
        rootDir.deleteRecursively()
    }

    @Test
    fun confirmingDuringRunningNowSyncThatEndsSuccessSendsBothSegments() {
        stores.journalConfirmationStore.confirm("sha256:other")
        SyncScheduler.enqueueNow(context, MAIN_STREAM)

        assertTrue(inFlightLatch.await(5, TimeUnit.SECONDS))
        stores.journalConfirmationStore.confirm("sha256:cert-1")
        SyncScheduler.enqueueAfterConfirm(context, MAIN_STREAM)
        releaseInFlightLatch.countDown()

        waitUntilWorkCompleted("solstone-sync-after-confirm")

        assertEquals(2, drainStore.segmentsByState(QueueState.EVICTED).size)
        assertTrue(recordingClient.requests.any { it.method == "GET" && it.path.startsWith(SEGMENTS_PATH) })
        assertTrue(recordingClient.requests.any { it.method == "POST" && it.path == INGEST_PATH })
    }

    @Test
    fun confirmingDuringRunningNowSyncThatEndsFailureSendsBothSegments() {
        stores.journalConfirmationStore.confirm("sha256:other")
        failFirstStatus.set(true)
        SyncScheduler.enqueueNow(context, MAIN_STREAM)

        assertTrue(inFlightLatch.await(5, TimeUnit.SECONDS))
        stores.journalConfirmationStore.confirm("sha256:cert-1")
        SyncScheduler.enqueueAfterConfirm(context, MAIN_STREAM)
        releaseInFlightLatch.countDown()

        waitUntilWorkCompleted("solstone-sync-now")
        waitUntilWorkCompleted("solstone-sync-after-confirm")

        assertEquals(2, drainStore.segmentsByState(QueueState.EVICTED).size)
        assertTrue(recordingClient.requests.any { it.method == "GET" && it.path.startsWith(SEGMENTS_PATH) })
        assertTrue(recordingClient.requests.any { it.method == "POST" && it.path == INGEST_PATH })
    }

    @Test
    fun confirmingDuringRunningNowSyncThatEndsRetrySendsBothSegments() {
        stores.journalConfirmationStore.confirm("sha256:other")
        retryFirstStatus.set(true)
        SyncScheduler.enqueueNow(context, MAIN_STREAM)

        assertTrue(inFlightLatch.await(5, TimeUnit.SECONDS))
        stores.journalConfirmationStore.confirm("sha256:cert-1")
        SyncScheduler.enqueueAfterConfirm(context, MAIN_STREAM)
        releaseInFlightLatch.countDown()

        waitUntilWorkCompleted("solstone-sync-after-confirm")

        assertEquals(2, drainStore.segmentsByState(QueueState.EVICTED).size)
        assertTrue(recordingClient.requests.any { it.method == "GET" && it.path.startsWith(SEGMENTS_PATH) })
        assertTrue(recordingClient.requests.any { it.method == "POST" && it.path == INGEST_PATH })
    }

    @Test
    fun confirmingWhilePeriodicHoldsDrainGateSendsBothSegments() {
        stores.journalConfirmationStore.confirm("sha256:other")
        SyncScheduler.enqueuePeriodic(context, MAIN_STREAM)

        val deadline = System.currentTimeMillis() + 5000L
        var periodicId: java.util.UUID? = null
        while (System.currentTimeMillis() < deadline) {
            val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK_NAME).get()
            if (infos.isNotEmpty()) {
                periodicId = infos.first().id
                break
            }
            Thread.sleep(50)
        }
        val workId = checkNotNull(periodicId) { "periodic work not found" }
        WorkManagerTestInitHelper.getTestDriver(context)?.setAllConstraintsMet(workId)
        WorkManagerTestInitHelper.getTestDriver(context)?.setPeriodDelayMet(workId)

        assertTrue(inFlightLatch.await(5, TimeUnit.SECONDS))
        stores.journalConfirmationStore.confirm("sha256:cert-1")
        SyncScheduler.enqueueAfterConfirm(context, MAIN_STREAM)
        releaseInFlightLatch.countDown()

        waitUntilWorkCompleted("solstone-sync-after-confirm")

        assertEquals(2, drainStore.segmentsByState(QueueState.EVICTED).size)
        assertTrue(recordingClient.requests.any { it.method == "GET" && it.path.startsWith(SEGMENTS_PATH) })
        assertTrue(recordingClient.requests.any { it.method == "POST" && it.path == INGEST_PATH })
    }

    @Test
    fun heldRunDoesNotConfirmBeforeStartAndConfirmSendsBothSegments() {
        stores.journalConfirmationStore.confirm("sha256:other")
        val inspection = stores.journalConfirmationStore.inspect()
        assertTrue(inspection !is StoreInspectResult.Ready || inspection.value.confirmed != "sha256:cert-1")

        SyncScheduler.enqueueNow(context, MAIN_STREAM)

        assertTrue(inFlightLatch.await(5, TimeUnit.SECONDS))
        releaseInFlightLatch.countDown()
        waitUntilWorkCompleted("solstone-sync-now")

        assertEquals(0, drainStore.segmentsByState(QueueState.EVICTED).size)
        assertEquals(2, drainStore.segmentsByState(QueueState.SEALED).size)

        stores.journalConfirmationStore.confirm("sha256:cert-1")
        SyncScheduler.enqueueAfterConfirm(context, MAIN_STREAM)

        waitUntilWorkCompleted("solstone-sync-after-confirm")

        assertEquals(2, drainStore.segmentsByState(QueueState.EVICTED).size)
        assertTrue(recordingClient.requests.any { it.method == "GET" && it.path.startsWith(SEGMENTS_PATH) })
        assertTrue(recordingClient.requests.any { it.method == "POST" && it.path == INGEST_PATH })
    }

    private fun waitUntilWorkCompleted(uniqueWorkName: String, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueWorkName).get()
            if (infos.isNotEmpty() && infos.all { it.state.isFinished }) {
                return
            }
            Thread.sleep(50)
        }
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueWorkName).get()
        assertTrue("Work $uniqueWorkName did not complete; states=${infos.map { it.state }}", infos.all { it.state.isFinished })
    }

    private fun createTestStores(root: File): Pair<SyncStores, FilePairingGraph> {
        val fakeProtector = object : SecretProtector {
            override fun protect(plaintext: ByteArray): ByteArray = plaintext
            override fun unprotect(ciphertext: ByteArray): ByteArray = ciphertext
        }
        val identFile = File(root, "identity.tsv")
        val credFile = File(root, "credential.pem")
        val epFile = File(root, "endpoint.txt")
        val commitFile = File(root, "pairing.commit")
        val jvFile = File(root, "journal-version.json")
        val jmFile = File(root, "journal-mark.json")
        val confirmFile = File(root, "confirmation.json")
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
            pushRegistration = null,
        )
        return stores to graph
    }

    private fun setupSpoolAndDrainStore(spoolDir: File): Pair<FakeDrainStore, List<String>> {
        val day1 = "2026-03-01"
        val segmentId1 = "$day1/phone/seg-1"
        val segmentDir1 = File(File(File(spoolDir, day1), "phone"), "seg-1").apply { mkdirs() }
        File(segmentDir1, "a.bin").writeBytes(byteArrayOf(1, 2, 3))
        val manifest1 = """
            solstone-bundle-manifest-v1
            day=$day1
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
        val segmentId2 = "$day2/phone/seg-2"
        val segmentDir2 = File(File(File(spoolDir, day2), "phone"), "seg-2").apply { mkdirs() }
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

        val segRow1 = SegmentRow(
            id = segmentId1,
            day = day1,
            stream = "phone",
            segment = "seg-1",
            dirSegment = "seg-1",
            state = QueueState.SEALED,
            byteSize = 3L,
            sealedAt = 2L,
            homeInstanceId = "inst-runtime-test",
            observerHandle = "obs",
            attemptCount = 0,
            lastStatusCode = null,
            lastAttemptAt = null,
            lastError = null,
        )
        val fileRow1 = SegmentFileRow(
            rowId = 0,
            segmentId = segmentId1,
            sourceId = "source1",
            name = "a.bin",
            sha256 = "sha-a",
            byteSize = 3L,
            mediaType = "application/octet-stream",
            captureStartEpochMs = 1L,
            captureEndEpochMs = 2L,
        )
        val segRow2 = SegmentRow(
            id = segmentId2,
            day = day2,
            stream = "phone",
            segment = "seg-2",
            dirSegment = "seg-2",
            state = QueueState.SEALED,
            byteSize = 3L,
            sealedAt = 20L,
            homeInstanceId = "inst-runtime-test",
            observerHandle = "obs",
            attemptCount = 0,
            lastStatusCode = null,
            lastAttemptAt = null,
            lastError = null,
        )
        val fileRow2 = SegmentFileRow(
            rowId = 0,
            segmentId = segmentId2,
            sourceId = "source2",
            name = "b.bin",
            sha256 = "sha-b",
            byteSize = 3L,
            mediaType = "application/octet-stream",
            captureStartEpochMs = 10L,
            captureEndEpochMs = 20L,
        )

        val store = FakeDrainStore(
            segRow1, segRow2,
            files = mapOf(
                segmentId1 to listOf(fileRow1),
                segmentId2 to listOf(fileRow2),
            ),
        )
        return store to listOf(segmentId1, segmentId2)
    }

    private fun standardMockResponses(
        method: String,
        path: String,
        body: ByteArray? = null,
    ): HttpResponse = when {
        method == "GET" && path == "/app/network/api/status" ->
            HttpResponse(200, emptyMap(), ByteArray(0))
        method == "GET" && path == "/app/network/api/clients/self" -> {
            val getJson = """{"protocol_version":1,"revision":1,"journal":{"name":"Home Journal","version":"1.2.3"},"reported":{"name":"Old Name","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null}"""
            HttpResponse(200, emptyMap(), getJson.toByteArray())
        }
        method == "PUT" && path == "/app/network/api/clients/self" -> {
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
        else -> HttpResponse(200, emptyMap(), ByteArray(0))
    }

    private data class RecordedReq(val method: String, val path: String, val body: ByteArray?)

    private class TestRecordingHttpClient(
        private val onRequest: (method: String, path: String, body: ByteArray?) -> HttpResponse,
    ) : PlHttpClient, Closeable {
        val requests = CopyOnWriteArrayList<RecordedReq>()
        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse {
            requests.add(RecordedReq(method, path, body))
            return onRequest(method, path, body)
        }
        override fun close() {}
    }

    private companion object {
        private var currentFactory: (() -> WorkerFactory)? = null
        @Volatile private var isInitialized = false

        @Synchronized
        fun ensureWorkManagerInitialized(context: Context) {
            if (!isInitialized) {
                val executor = Executors.newCachedThreadPool()
                val delegatingFactory = object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker? {
                        return currentFactory?.invoke()?.createWorker(appContext, workerClassName, workerParameters)
                    }
                }
                val config = Configuration.Builder()
                    .setExecutor(executor)
                    .setWorkerFactory(delegatingFactory)
                    .build()
                WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
                isInitialized = true
            }
        }
    }
}
