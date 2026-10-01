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
            return when {
                method == "GET" && path == "/app/network/api/status" ->
                    HttpResponse(200, emptyMap(), ByteArray(0))
                method == "GET" && path.startsWith(SEGMENTS_PATH) ->
                    HttpResponse(200, emptyMap(), """{"items":[{"key":"remote","observed":true,"files":[]}],"total":1,"protocol_version":3}""".toByteArray())
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
}
