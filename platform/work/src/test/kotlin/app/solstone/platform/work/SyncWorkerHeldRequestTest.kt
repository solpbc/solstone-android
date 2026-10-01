// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.JournalConfirmationPolicy
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
import app.solstone.core.push.DistributorPort
import app.solstone.core.push.DistributorResolution
import app.solstone.core.push.PushRegistrationCoordinator
import app.solstone.core.push.PushRegistrationFile
import app.solstone.core.push.PushRegistrationState
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncWorkerHeldRequestTest {
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

    private fun testHome() = PairedHome(
        instanceId = "home-1",
        homeLabel = "Home",
        relayOrigin = "https://link.solstone.app",
        caChainFingerprint = "sha256:ca-1",
        clientCertFingerprint = "sha256:cert-1",
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

    private fun createStores(): Triple<SyncStores, FilePairingGraph, TestDistributorPort> {
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

        val pushDir = File(root, "push").apply { mkdirs() }
        PushRegistrationFile.write(File(pushDir, PushRegistrationFile.FILE_NAME), PushRegistrationState(ownerOn = true)) {}
        val port = TestDistributorPort()
        val pushCoord = PushRegistrationCoordinator(
            directory = pushDir,
            port = port,
            enabled = true,
            pushKeys = graph,
            pairingNow = { mutator.currentPairingGeneration() },
            log = {},
            enqueue = {},
        )

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

    private class MockPlHttpClient(
        private val onRequest: (method: String, path: String, body: ByteArray?) -> HttpResponse,
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

    @Test
    fun heldRunEmitsStatusVersionAndRelayAccessWithoutIngestOrPush() {
        val (stores, graph, port) = createStores()
        graph.installOrReplace(testHome(), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        val spoolDir = temp.newFolder()
        val segDir = File(File(File(spoolDir, WORK_TEST_DAY), MAIN_STREAM), "seg-1").apply { mkdirs() }
        File(segDir, "a.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(segDir, "manifest").writeText("solstone-bundle-manifest-v1\nday=$WORK_TEST_DAY\nsegment=seg-1\nstartEpochMs=1\nendEpochMs=2\nzoneId=UTC\nutcOffsetSeconds=0\n[files]\ns1\ta.bin\tsha-a\t3\tapplication/octet-stream\t1\t2\n[gaps]\n")
        val segRow = segment("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").copy(day = WORK_TEST_DAY, stream = MAIN_STREAM, segment = "seg-1", dirSegment = "seg-1", byteSize = 3)
        val fileRow = file("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").copy(sourceId = "s1", name = "a.bin", sha256 = "sha-a", byteSize = 3)
        val drainStore = FakeDrainStore(segRow, files = mapOf("$WORK_TEST_DAY/$MAIN_STREAM/seg-1" to listOf(fileRow)))

        val versionLatch = CountDownLatch(1)
        val relayLatch = CountDownLatch(1)

        val client = MockPlHttpClient { method, path, _ ->
            when {
                method == "GET" && path == "/app/network/api/status" ->
                    HttpResponse(200, emptyMap(), ByteArray(0))
                method == "GET" && path == "/app/network/api/clients/self" -> {
                    val getJson = """{"protocol_version":1,"revision":1,"journal":{"name":"Home Journal","version":"1.2.3"},"reported":{"name":"Old Name","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null}"""
                    HttpResponse(200, emptyMap(), getJson.toByteArray())
                }
                method == "PUT" && path == "/app/network/api/clients/self" -> {
                    versionLatch.countDown()
                    val putJson = """{"protocol_version":1,"revision":2,"reported":{"name":"Phone","platform":"android","device_type":"phone","app_id":"app.solstone.phone","app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null,"journal":{"name":"Home Journal","version":"1.2.3"}}"""
                    HttpResponse(200, emptyMap(), putJson.toByteArray())
                }
                method == "GET" && path == "/app/network/api/relay/access" -> {
                    relayLatch.countDown()
                    val relayJson = """{"protocol_version":1,"instance_id":"home-1","ca_chain_fingerprint":"sha256:ca-1","client_cert_fingerprint":"sha256:cert-1","relay_origin":"https://link.solstone.app","device_token":"token-1","expires_at":"2030-01-01T00:00:00Z"}"""
                    HttpResponse(200, emptyMap(), relayJson.toByteArray())
                }
                else -> HttpResponse(200, emptyMap(), ByteArray(0))
            }
        }

        val outcome = completeSyncRun(
            openStores = { stores },
            spoolDir = spoolDir,
            drainStore = drainStore,
            openClient = { _, _ -> client },
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
        )

        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertEquals(listOf("kind=sync outcome=success held=unconfirmed"), capturedDiag)

        assertTrue(versionLatch.await(5, TimeUnit.SECONDS))
        assertTrue(relayLatch.await(5, TimeUnit.SECONDS))

        val paths = client.requests.map { "${it.method} ${it.path}" }
        assertTrue(paths.contains("GET /app/network/api/status"))
        assertTrue(paths.contains("GET /app/network/api/clients/self") || paths.contains("PUT /app/network/api/clients/self"))
        assertTrue(paths.contains("GET /app/network/api/relay/access"))

        // Verify owner material / unconfirmed prohibited endpoints are NOT called
        assertFalse(client.requests.any { it.path == "/app/network/api/identity" })
        assertFalse(client.requests.any { it.path.startsWith("/app/network/api/clients/") && it.method == "DELETE" })
        assertFalse(client.requests.any { it.path == INGEST_PATH })
        assertFalse(client.requests.any { it.path.startsWith(SEGMENTS_PATH) })
        assertFalse(client.requests.any { it.path == "/api/push/vapid-key" })
        assertFalse(client.requests.any { it.path == "/api/push/register" })
        assertFalse(client.requests.any { it.path == "/api/push/subscriptions" })
        assertTrue(port.registeredVapidKeys.isEmpty())

        // Segments stay sealed
        assertEquals(QueueState.SEALED, drainStore.row("$WORK_TEST_DAY/$MAIN_STREAM/seg-1").state)
    }
}
