// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.DurableTxnStep
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.identity.PairingGraphSnapshot
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
import kotlin.test.assertTrue

class FilePairingGraphSyncDurabilityTest {
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

    private fun testCred(key: String = "key-1", cert: String = "cert-1") = ClientCredential(
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n$key\n-----END PRIVATE KEY-----\n",
        clientCertPem = "-----BEGIN CERTIFICATE-----\n$cert\n-----END CERTIFICATE-----\n",
        caChainPem = listOf("-----BEGIN CERTIFICATE-----\nca-1\n-----END CERTIFICATE-----\n"),
    )

    private class RecordingClient(
        private val refuseIngestWith: Int? = null,
        private val onIngest: () -> Unit = {},
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
            if (method == "POST" && path == INGEST_PATH) onIngest()
            return when {
                method == "GET" && path == "/app/network/api/status" ->
                    HttpResponse(200, emptyMap(), ByteArray(0))
                method == "GET" && path.startsWith(SEGMENTS_PATH) ->
                    HttpResponse(200, emptyMap(), """{"items":[{"key":"remote","observed":true,"files":[]}],"total":1,"protocol_version":3}""".toByteArray())
                method == "POST" && path == INGEST_PATH && refuseIngestWith != null ->
                    HttpResponse(refuseIngestWith, emptyMap(), ByteArray(0))
                method == "POST" && path == INGEST_PATH -> {
                    val bodyText = body?.toString(Charsets.UTF_8) ?: ""
                    val name = if (bodyText.contains("b.bin")) "b.bin" else "a.bin"
                    val sha = if (bodyText.contains("b.bin")) "sha-b" else "sha-a"
                    HttpResponse(200, emptyMap(), """{"status":"ok","segment":"srv-1","file_descriptors":[{"submitted":"$name","written":"$name","size":3,"sha256":"$sha","disposition":"written"}]}""".toByteArray())
                }
                else ->
                    HttpResponse(200, emptyMap(), ByteArray(0))
            }
        }
        override fun close() {}
    }

    /**
     * Requests that carry segment material: a day's listing or an upload. The journal-version
     * refresh a sync starts runs on its own thread and can still reach a journal after the run
     * returns, so a count of every request races; it carries no segment material.
     */
    private fun RecordingClient.segmentMaterialRequests(): Int =
        requests.count { it.path == INGEST_PATH || it.path.startsWith(SEGMENTS_PATH) }

    @Test
    fun realFilePairingGraphConfirmedSyncSendsAndEvictsSegments() {
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

        // Install pairing A
        graph.installOrReplace(testHome("sha256:cert-1", "home-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        // Confirm pairing A
        assertTrue(confirmCurrentJournal(graph, confirmStore, "sha256:cert-1"))
        val inspect = confirmStore.inspect()
        assertTrue(inspect is StoreInspectResult.Ready)
        assertEquals("sha256:cert-1", inspect.value.confirmed)

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
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
        )

        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertEquals(listOf("kind=sync outcome=success"), capturedDiag)
        assertEquals(QueueState.EVICTED, drainStore.row("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").state)
        assertTrue(client.requests.any { it.path == INGEST_PATH })
    }

    @Test
    fun rolledBackRepairSendsOriginalPairingSegments() {
        val root = temp.newFolder()
        val identFile = File(root, "identity.tsv")
        val credFile = File(root, "credential.pem")
        val epFile = File(root, "endpoint.txt")
        val commitFile = File(root, "pairing.commit")
        val confirmFile = File(root, "journal_confirmation.json")
        val jvFile = File(root, "journal_version.tsv")
        val jmFile = File(root, "journal_mark.json")
        val pushKeyFile = File(root, "push-key.bin")

        var crash = false
        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = fakeProtector,
            pushKeyFile = pushKeyFile,
            pushKeyProtector = fakeProtector,
            stepHook = { step, _ ->
                if (step == DurableTxnStep.STAGING_WRITE && crash) {
                    throw RuntimeException("crash before commit")
                }
            },
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

        // Install pairing A
        graph.installOrReplace(testHome("sha256:cert-1", "home-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        assertTrue(confirmCurrentJournal(graph, confirmStore, "sha256:cert-1"))

        // Attempt install of pairing B with crash during staging write -> rollback restores A
        crash = true
        val result = graph.installOrReplace(
            testHome("sha256:cert-2", "home-2"),
            testCred("key-2", "cert-2"),
            DirectEndpoint("10.0.0.2", 7657),
            true,
        )
        assertTrue(result is GraphMutationResult.PersistenceFailed)

        // Two segments
        val spoolDir = temp.newFolder()
        val segDir1 = File(File(File(spoolDir, WORK_TEST_DAY), MAIN_STREAM), "seg-1").apply { mkdirs() }
        File(segDir1, "a.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(segDir1, "manifest").writeText("solstone-bundle-manifest-v1\nday=$WORK_TEST_DAY\nsegment=seg-1\nstartEpochMs=1\nendEpochMs=2\nzoneId=UTC\nutcOffsetSeconds=0\n[files]\ns1\ta.bin\tsha-a\t3\tapplication/octet-stream\t1\t2\n[gaps]\n")
        val segRow1 = segment("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").copy(day = WORK_TEST_DAY, stream = MAIN_STREAM, segment = "seg-1", dirSegment = "seg-1", byteSize = 3)
        val fileRow1 = file("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").copy(sourceId = "s1", name = "a.bin", sha256 = "sha-a", byteSize = 3)

        val day2 = "2026-03-02"
        val segDir2 = File(File(File(spoolDir, day2), MAIN_STREAM), "seg-2").apply { mkdirs() }
        File(segDir2, "b.bin").writeBytes(byteArrayOf(4, 5, 6))
        File(segDir2, "manifest").writeText("solstone-bundle-manifest-v1\nday=$day2\nsegment=seg-2\nstartEpochMs=10\nendEpochMs=20\nzoneId=UTC\nutcOffsetSeconds=0\n[files]\ns2\tb.bin\tsha-b\t3\tapplication/octet-stream\t10\t20\n[gaps]\n")
        val segRow2 = segment("$day2/$MAIN_STREAM/seg-2").copy(day = day2, stream = MAIN_STREAM, segment = "seg-2", dirSegment = "seg-2", byteSize = 3)
        val fileRow2 = file("$day2/$MAIN_STREAM/seg-2").copy(sourceId = "s2", name = "b.bin", sha256 = "sha-b", byteSize = 3)

        val drainStore = FakeDrainStore(
            segRow1, segRow2,
            files = mapOf(
                "$WORK_TEST_DAY/$MAIN_STREAM/seg-1" to listOf(fileRow1),
                "$day2/$MAIN_STREAM/seg-2" to listOf(fileRow2),
            ),
        )

        val client = RecordingClient()

        val outcome = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> client },
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
        )

        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertEquals(listOf("kind=sync outcome=success"), capturedDiag)
        assertEquals(QueueState.EVICTED, drainStore.row("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").state)
        assertEquals(QueueState.EVICTED, drainStore.row("$day2/$MAIN_STREAM/seg-2").state)
        assertTrue(client.requests.any { it.path.startsWith(SEGMENTS_PATH) })
        assertTrue(client.requests.any { it.path == INGEST_PATH })
    }

    @Test
    fun forgetRollbackSendsNothing() {
        val root = temp.newFolder()
        val identFile = File(root, "identity.tsv")
        val credFile = File(root, "credential.pem")
        val epFile = File(root, "endpoint.txt")
        val commitFile = File(root, "pairing.commit")
        val confirmFile = File(root, "journal_confirmation.json")
        val jvFile = File(root, "journal_version.tsv")
        val jmFile = File(root, "journal_mark.json")
        val pushKeyFile = File(root, "push-key.bin")

        var crashForget = false
        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = fakeProtector,
            pushKeyFile = pushKeyFile,
            pushKeyProtector = fakeProtector,
            stepHook = { step, _ ->
                if (step == DurableTxnStep.STAGING_WRITE && crashForget) {
                    throw RuntimeException("crash during forget staging write")
                }
            },
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

        // Install pairing A
        graph.installOrReplace(testHome("sha256:cert-1", "home-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        assertTrue(confirmCurrentJournal(graph, confirmStore, "sha256:cert-1"))

        // Install pairing B directly (without settleBeforeInstall)
        val installBResult = graph.installOrReplace(
            testHome("sha256:cert-2", "home-2"),
            testCred("key-2", "cert-2"),
            DirectEndpoint("10.0.0.2", 7657),
            true,
        )
        assertTrue(installBResult is GraphMutationResult.Applied)

        // Forget pairing with crash during staging write -> rollback leaves B committed
        crashForget = true
        val forgetResult = graph.forget()
        assertTrue(forgetResult is GraphMutationResult.PersistenceFailed)

        val committed = graph.currentSnapshot() as PairingGraphSnapshot.Committed
        assertEquals("home-2", committed.home.instanceId)
        assertEquals("sha256:cert-2", committed.home.clientCertFingerprint)

        val inspect = confirmStore.inspect()
        assertTrue(inspect is StoreInspectResult.Ready)
        assertEquals("sha256:cert-1", inspect.value.confirmed)

        // Two segments
        val spoolDir = temp.newFolder()
        val segDir1 = File(File(File(spoolDir, WORK_TEST_DAY), MAIN_STREAM), "seg-1").apply { mkdirs() }
        File(segDir1, "a.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(segDir1, "manifest").writeText("solstone-bundle-manifest-v1\nday=$WORK_TEST_DAY\nsegment=seg-1\nstartEpochMs=1\nendEpochMs=2\nzoneId=UTC\nutcOffsetSeconds=0\n[files]\ns1\ta.bin\tsha-a\t3\tapplication/octet-stream\t1\t2\n[gaps]\n")
        val segRow1 = segment("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").copy(day = WORK_TEST_DAY, stream = MAIN_STREAM, segment = "seg-1", dirSegment = "seg-1", byteSize = 3)
        val fileRow1 = file("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").copy(sourceId = "s1", name = "a.bin", sha256 = "sha-a", byteSize = 3)

        val day2 = "2026-03-02"
        val segDir2 = File(File(File(spoolDir, day2), MAIN_STREAM), "seg-2").apply { mkdirs() }
        File(segDir2, "b.bin").writeBytes(byteArrayOf(4, 5, 6))
        File(segDir2, "manifest").writeText("solstone-bundle-manifest-v1\nday=$day2\nsegment=seg-2\nstartEpochMs=10\nendEpochMs=20\nzoneId=UTC\nutcOffsetSeconds=0\n[files]\ns2\tb.bin\tsha-b\t3\tapplication/octet-stream\t10\t20\n[gaps]\n")
        val segRow2 = segment("$day2/$MAIN_STREAM/seg-2").copy(day = day2, stream = MAIN_STREAM, segment = "seg-2", dirSegment = "seg-2", byteSize = 3)
        val fileRow2 = file("$day2/$MAIN_STREAM/seg-2").copy(sourceId = "s2", name = "b.bin", sha256 = "sha-b", byteSize = 3)

        val drainStore = FakeDrainStore(
            segRow1, segRow2,
            files = mapOf(
                "$WORK_TEST_DAY/$MAIN_STREAM/seg-1" to listOf(fileRow1),
                "$day2/$MAIN_STREAM/seg-2" to listOf(fileRow2),
            ),
        )

        val client = RecordingClient()

        val outcome = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> client },
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
        )

        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertEquals(listOf("kind=sync outcome=success held=unconfirmed"), capturedDiag)
        assertEquals(QueueState.SEALED, drainStore.row("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").state)
        assertEquals(QueueState.SEALED, drainStore.row("$day2/$MAIN_STREAM/seg-2").state)
        assertFalse(client.requests.any { it.path.startsWith(SEGMENTS_PATH) })
        assertFalse(client.requests.any { it.path == INGEST_PATH })
    }

    private data class Paired(val graph: FilePairingGraph, val stores: SyncStores, val confirmStore: FileJournalConfirmationStore)

    private fun pairedStores(): Paired {
        val root = temp.newFolder()
        val identFile = File(root, "identity.tsv")
        val credFile = File(root, "credential.pem")
        val epFile = File(root, "endpoint.txt")
        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = File(root, "pairing.commit"),
            protector = fakeProtector,
            pushKeyFile = File(root, "push-key.bin"),
            pushKeyProtector = fakeProtector,
        )
        val mutator = PublisherIdentityMutatorAdapter(graph)
        val jvStore = FileJournalVersionStore(File(root, "journal_version.tsv"))
        val jmStore = FileJournalMarkStore(File(root, "journal_mark.json"))
        val confirmStore = FileJournalConfirmationStore(File(root, "journal_confirmation.json"))
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
        // Journal A, paired and confirmed.
        graph.installOrReplace(testHome("sha256:cert-1", "home-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        assertTrue(confirmCurrentJournal(graph, confirmStore, "sha256:cert-1"))
        return Paired(graph, stores, confirmStore)
    }

    private val heldId1 = "$WORK_TEST_DAY/$MAIN_STREAM/seg-1"
    private val heldDay2 = "2026-03-02"
    private val heldId2 = "$heldDay2/$MAIN_STREAM/seg-2"

    /** Two waiting segments on two days; seg-1 is the older. */
    private fun twoHeldSegments(spoolDir: File): FakeDrainStore {
        val segDir1 = File(File(File(spoolDir, WORK_TEST_DAY), MAIN_STREAM), "seg-1").apply { mkdirs() }
        File(segDir1, "a.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(segDir1, "manifest").writeText("solstone-bundle-manifest-v1\nday=$WORK_TEST_DAY\nsegment=seg-1\nstartEpochMs=1\nendEpochMs=2\nzoneId=UTC\nutcOffsetSeconds=0\n[files]\ns1\ta.bin\tsha-a\t3\tapplication/octet-stream\t1\t2\n[gaps]\n")
        val segRow1 = segment(heldId1).copy(day = WORK_TEST_DAY, stream = MAIN_STREAM, segment = "seg-1", dirSegment = "seg-1", byteSize = 3)
        val fileRow1 = file(heldId1).copy(sourceId = "s1", name = "a.bin", sha256 = "sha-a", byteSize = 3)

        val segDir2 = File(File(File(spoolDir, heldDay2), MAIN_STREAM), "seg-2").apply { mkdirs() }
        File(segDir2, "b.bin").writeBytes(byteArrayOf(4, 5, 6))
        File(segDir2, "manifest").writeText("solstone-bundle-manifest-v1\nday=$heldDay2\nsegment=seg-2\nstartEpochMs=10\nendEpochMs=20\nzoneId=UTC\nutcOffsetSeconds=0\n[files]\ns2\tb.bin\tsha-b\t3\tapplication/octet-stream\t10\t20\n[gaps]\n")
        val segRow2 = segment(heldId2).copy(day = heldDay2, stream = MAIN_STREAM, segment = "seg-2", dirSegment = "seg-2", byteSize = 3, sealedAt = 2)
        val fileRow2 = file(heldId2).copy(sourceId = "s2", name = "b.bin", sha256 = "sha-b", byteSize = 3)

        return FakeDrainStore(
            segRow1, segRow2,
            files = mapOf(heldId1 to listOf(fileRow1), heldId2 to listOf(fileRow2)),
        )
    }

    private fun runSync(
        stores: SyncStores,
        spoolDir: File,
        drainStore: FakeDrainStore,
        openClient: (SyncTransport, ClientCredential) -> PlHttpClient,
    ): SyncOutcome = completeSyncRun(
        openStores = { stores },
        spoolDir = spoolDir,
        drainStore = drainStore,
        openClient = openClient,
        localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
    )

    /**
     * Anything the device holds and has not delivered goes to the journal it is paired with now,
     * once the owner confirms that journal's mark. Unpairing never strands it, and the journal the
     * owner left has no say: not even its refusal of a segment while the owner was unpairing.
     */
    @Test
    fun heldSegmentsGoToTheNextJournalAfterForgetPairAndConfirm() {
        val (graph, stores, confirmStore) = pairedStores()
        val spoolDir = temp.newFolder()
        val drainStore = twoHeldSegments(spoolDir)

        // The owner unpairs from A mid-sync: A has already dropped this device, so it refuses
        // the segment in flight.
        val journalA = RecordingClient(refuseIngestWith = 401)
        val journalB = RecordingClient()
        val openClient: (SyncTransport, ClientCredential) -> PlHttpClient = { _, credential ->
            if (credential.clientCertPem.contains("cert-2")) journalB else journalA
        }

        assertEquals(SyncOutcome.FAILURE, runSync(stores, spoolDir, drainStore, openClient))
        assertEquals(QueueState.FAILED, drainStore.row(heldId1).state)
        assertEquals(401, drainStore.row(heldId1).lastStatusCode)
        assertEquals(QueueState.SEALED, drainStore.row(heldId2).state)
        assertTrue(graph.forget() is GraphMutationResult.Cleared)
        val requestsToA = journalA.segmentMaterialRequests()

        // Journal B, paired but its mark not yet confirmed: nothing goes to it.
        val installB = graph.installOrReplace(
            testHome("sha256:cert-2", "home-2"),
            testCred("key-2", "cert-2"),
            DirectEndpoint("10.0.0.2", 7657),
            true,
        )
        assertTrue(installB is GraphMutationResult.Applied)
        assertEquals(SyncOutcome.SUCCESS, runSync(stores, spoolDir, drainStore, openClient))
        assertFalse(journalB.requests.any { it.path == INGEST_PATH })
        assertEquals(2, drainStore.pendingCount(MAIN_STREAM))

        // The owner confirms B's mark: everything A was holding goes to B.
        assertTrue(confirmCurrentJournal(graph, confirmStore, "sha256:cert-2"))
        assertEquals(SyncOutcome.SUCCESS, runSync(stores, spoolDir, drainStore, openClient))

        assertEquals(QueueState.EVICTED, drainStore.row(heldId1).state)
        assertEquals(QueueState.EVICTED, drainStore.row(heldId2).state)
        assertEquals(0, drainStore.pendingCount(MAIN_STREAM))
        val sentToB = journalB.requests
            .filter { it.path == INGEST_PATH }
            .map { it.body!!.toString(Charsets.UTF_8) }
        assertTrue(sentToB.any { it.contains("a.bin") })
        assertTrue(sentToB.any { it.contains("b.bin") })
        assertEquals(requestsToA, journalA.segmentMaterialRequests())
    }

    /**
     * A back-off belongs to the journal that earned it. Segments a journal kept failing with
     * server errors wait out their back-off there, but once the owner leaves that journal, pairs
     * another and confirms its mark, they go to the new journal on its first sync, not hours later.
     */
    @Test
    fun backOffEarnedAtTheJournalLeftDoesNotDelayTheNextJournal() {
        val (graph, stores, confirmStore) = pairedStores()
        val spoolDir = temp.newFolder()
        val drainStore = twoHeldSegments(spoolDir)

        val journalA = RecordingClient(refuseIngestWith = 500)
        val journalB = RecordingClient()
        val openClient: (SyncTransport, ClientCredential) -> PlHttpClient = { _, credential ->
            if (credential.clientCertPem.contains("cert-2")) journalB else journalA
        }

        // Journal A fails both segments with a server error, so each backs off there.
        runSync(stores, spoolDir, drainStore, openClient)
        for (id in listOf(heldId1, heldId2)) {
            val row = drainStore.row(id)
            assertEquals(QueueState.FAILED, row.state)
            assertEquals(500, row.lastStatusCode)
            assertEquals(1, row.attemptCount)
            assertEquals("home-1", row.homeInstanceId)
        }
        // While A is still the journal, its back-off holds: an immediate sync sends A nothing.
        val ingestsToA = journalA.requests.count { it.path == INGEST_PATH }
        assertEquals(2, ingestsToA)
        runSync(stores, spoolDir, drainStore, openClient)
        assertEquals(ingestsToA, journalA.requests.count { it.path == INGEST_PATH })
        // Several more failures at A push the back-off toward its four-hour cap.
        for (id in listOf(heldId1, heldId2)) {
            drainStore.recordAttempt(id, 5, System.currentTimeMillis(), "home-1")
        }

        // Forget A, pair B: nothing goes to B before the owner confirms its mark.
        assertTrue(graph.forget() is GraphMutationResult.Cleared)
        val requestsToA = journalA.segmentMaterialRequests()
        val installB = graph.installOrReplace(
            testHome("sha256:cert-2", "home-2"),
            testCred("key-2", "cert-2"),
            DirectEndpoint("10.0.0.2", 7657),
            true,
        )
        assertTrue(installB is GraphMutationResult.Applied)
        assertEquals(SyncOutcome.SUCCESS, runSync(stores, spoolDir, drainStore, openClient))
        assertFalse(journalB.requests.any { it.path == INGEST_PATH })

        // Confirmed: B's first sync takes both segments, whatever A's back-off said.
        assertTrue(confirmCurrentJournal(graph, confirmStore, "sha256:cert-2"))
        assertEquals(SyncOutcome.SUCCESS, runSync(stores, spoolDir, drainStore, openClient))

        assertEquals(QueueState.EVICTED, drainStore.row(heldId1).state)
        assertEquals(QueueState.EVICTED, drainStore.row(heldId2).state)
        assertEquals(0, drainStore.pendingCount(MAIN_STREAM))
        val sentToB = journalB.requests
            .filter { it.path == INGEST_PATH }
            .map { it.body!!.toString(Charsets.UTF_8) }
        assertTrue(sentToB.any { it.contains("a.bin") })
        assertTrue(sentToB.any { it.contains("b.bin") })
        assertEquals(requestsToA, journalA.segmentMaterialRequests())
    }

    /** An unpair while a sync is sending stops the sending at the journal the owner left. */
    @Test
    fun unpairDuringASyncSendsNothingMoreToTheJournalLeft() {
        val paired = pairedStores()
        val spoolDir = temp.newFolder()
        val drainStore = twoHeldSegments(spoolDir)
        val journalA = RecordingClient(onIngest = {
            assertTrue(paired.graph.forget() is GraphMutationResult.Cleared)
        })

        assertEquals(SyncOutcome.RETRY, runSync(paired.stores, spoolDir, drainStore) { _, _ -> journalA })

        assertEquals(1, journalA.requests.count { it.path == INGEST_PATH })
        assertEquals(1, journalA.requests.count { it.path.startsWith(SEGMENTS_PATH) })
        assertEquals(QueueState.EVICTED, drainStore.row(heldId1).state)
        val waiting = drainStore.row(heldId2)
        assertEquals(QueueState.SEALED, waiting.state)
        assertEquals(0, waiting.attemptCount)
        assertEquals(null, waiting.lastStatusCode)
        assertEquals(null, waiting.lastError)
        assertEquals(1, drainStore.pendingCount(MAIN_STREAM))
    }
}
