// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.model.QueueState
import app.solstone.core.observer.INGEST_PATH
import app.solstone.core.observer.SEGMENTS_PATH
import app.solstone.core.pl.ClientReportedDescription
import app.solstone.core.pl.HttpResponse
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.pl.JournalIdentityRefreshCoordinator
import app.solstone.core.pl.JournalVersionRefreshCoordinator
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.RelayAccessRefreshCoordinator
import app.solstone.platform.identity.file.FileClientCredentialStore
import app.solstone.platform.identity.file.FileEndpointStore
import app.solstone.platform.identity.file.FileIdentityStore
import app.solstone.platform.identity.file.FileJournalConfirmationStore
import app.solstone.platform.identity.file.FileJournalMarkStore
import app.solstone.platform.identity.file.FileJournalVersionStore
import app.solstone.platform.identity.file.FilePairingGraph
import app.solstone.platform.identity.file.SecretProtector
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SyncWorkerCurrencyTest {
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

    private fun testHome(cert: String, id: String) = PairedHome(
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

    private fun createStores(): Pair<SyncStores, FilePairingGraph> {
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

    private class RecordingClient : PlHttpClient, Closeable {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse {
            requests.add(RecordedRequest(method, path, headers, body))
            return HttpResponse(200, emptyMap(), ByteArray(0))
        }
        override fun close() {}
    }

    @Test
    fun heldSyncRunDetectsPairingReplacementBeforeDialAndReturnsSuccessWithoutIngest() {
        val (stores, graph) = createStores()
        graph.installOrReplace(testHome("sha256:cert-a", "home-A"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        val spoolDir = temp.newFolder()
        val segDir = File(File(File(spoolDir, WORK_TEST_DAY), MAIN_STREAM), "seg-1").apply { mkdirs() }
        File(segDir, "a.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(segDir, "manifest").writeText("solstone-bundle-manifest-v1\nday=$WORK_TEST_DAY\nsegment=seg-1\nstartEpochMs=1\nendEpochMs=2\nzoneId=UTC\nutcOffsetSeconds=0\n[files]\ns1\ta.bin\tsha-a\t3\tapplication/octet-stream\t1\t2\n[gaps]\n")
        val segRow = segment("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").copy(day = WORK_TEST_DAY, stream = MAIN_STREAM, segment = "seg-1", dirSegment = "seg-1", byteSize = 3)
        val fileRow = file("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").copy(sourceId = "s1", name = "a.bin", sha256 = "sha-a", byteSize = 3)
        val drainStore = FakeDrainStore(segRow, files = mapOf("$WORK_TEST_DAY/$MAIN_STREAM/seg-1" to listOf(fileRow)))

        val client = RecordingClient()

        val outcome = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> client },
            afterCredentialsFrozen = {
                // Mutate/replace pairing to B before dial
                graph.installOrReplace(testHome("sha256:cert-b", "home-B"), testCred(), DirectEndpoint("10.0.0.2", 7657), true)
            },
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
        )

        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertEquals(listOf("kind=sync outcome=success held=unconfirmed"), capturedDiag)
        assertEquals(QueueState.SEALED, drainStore.row("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").state)
        assertFalse(client.requests.any { it.path == INGEST_PATH || it.path.startsWith(SEGMENTS_PATH) })
        assertEquals(0, client.requests.size)
    }

    @Test
    fun confirmedSyncRunDetectsPairingReplacementBeforeDialAndReturnsRetryWithoutIngest() {
        val (stores, graph) = createStores()
        graph.installOrReplace(testHome("sha256:cert-a", "home-A"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        confirmCurrentJournal(graph, stores.journalConfirmationStore, "sha256:cert-a")

        val spoolDir = temp.newFolder()
        val segDir = File(File(File(spoolDir, WORK_TEST_DAY), MAIN_STREAM), "seg-1").apply { mkdirs() }
        File(segDir, "a.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(segDir, "manifest").writeText("solstone-bundle-manifest-v1\nday=$WORK_TEST_DAY\nsegment=seg-1\nstartEpochMs=1\nendEpochMs=2\nzoneId=UTC\nutcOffsetSeconds=0\n[files]\ns1\ta.bin\tsha-a\t3\tapplication/octet-stream\t1\t2\n[gaps]\n")
        val segRow = segment("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").copy(day = WORK_TEST_DAY, stream = MAIN_STREAM, segment = "seg-1", dirSegment = "seg-1", byteSize = 3)
        val fileRow = file("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").copy(sourceId = "s1", name = "a.bin", sha256 = "sha-a", byteSize = 3)
        val drainStore = FakeDrainStore(segRow, files = mapOf("$WORK_TEST_DAY/$MAIN_STREAM/seg-1" to listOf(fileRow)))

        val client = RecordingClient()

        val outcome = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> client },
            afterCredentialsFrozen = {
                // Mutate/replace pairing to B before dial
                graph.installOrReplace(testHome("sha256:cert-b", "home-B"), testCred(), DirectEndpoint("10.0.0.2", 7657), true)
            },
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
        )

        assertEquals(SyncOutcome.RETRY, outcome)
        assertEquals(listOf("kind=sync outcome=retry"), capturedDiag)
        assertEquals(QueueState.SEALED, drainStore.row("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").state)
        assertFalse(client.requests.any { it.path == INGEST_PATH || it.path.startsWith(SEGMENTS_PATH) })
        assertEquals(0, client.requests.size)
    }

    @Test
    fun heldStatusProbeAvailabilityReturnsRetryWhenAccessStillCurrent() {
        val (stores, graph) = createStores()
        graph.installOrReplace(testHome("sha256:cert-a", "home-A"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        val spoolDir = temp.newFolder()
        val segDir = File(File(File(spoolDir, WORK_TEST_DAY), MAIN_STREAM), "seg-1").apply { mkdirs() }
        File(segDir, "a.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(segDir, "manifest").writeText("solstone-bundle-manifest-v1\nday=$WORK_TEST_DAY\nsegment=seg-1\nstartEpochMs=1\nendEpochMs=2\nzoneId=UTC\nutcOffsetSeconds=0\n[files]\ns1\ta.bin\tsha-a\t3\tapplication/octet-stream\t1\t2\n[gaps]\n")
        val segRow = segment("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").copy(day = WORK_TEST_DAY, stream = MAIN_STREAM, segment = "seg-1", dirSegment = "seg-1", byteSize = 3)
        val fileRow = file("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").copy(sourceId = "s1", name = "a.bin", sha256 = "sha-a", byteSize = 3)
        val drainStore = FakeDrainStore(segRow, files = mapOf("$WORK_TEST_DAY/$MAIN_STREAM/seg-1" to listOf(fileRow)))

        val throwingClient = object : PlHttpClient, Closeable {
            override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Int): HttpResponse {
                throw java.io.IOException("connection refused")
            }
            override fun close() {}
        }

        val outcome = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> throwingClient },
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
        )

        assertEquals(SyncOutcome.RETRY, outcome)
        assertEquals(listOf("kind=sync outcome=retry"), capturedDiag)
        assertEquals(QueueState.SEALED, drainStore.row("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").state)
    }
}
