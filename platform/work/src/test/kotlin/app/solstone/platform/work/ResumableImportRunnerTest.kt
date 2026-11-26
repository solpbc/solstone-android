// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.JournalVersionRecord
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.JournalIdentityRefreshCoordinator
import app.solstone.core.pl.JournalVersionRefreshCoordinator
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.RelayAccessRefreshCoordinator
import app.solstone.core.pl.StreamingBody
import app.solstone.core.pl.parseJson
import app.solstone.platform.identity.file.FileClientCredentialStore
import app.solstone.platform.identity.file.FileEndpointStore
import app.solstone.platform.identity.file.FileIdentityStore
import app.solstone.platform.identity.file.FileJournalConfirmationStore
import app.solstone.platform.identity.file.FileJournalMarkStore
import app.solstone.platform.identity.file.FileJournalVersionStore
import app.solstone.platform.identity.file.FilePairingGraph
import app.solstone.platform.identity.file.SecretProtector
import app.solstone.platform.persistence.room.ImportCustodyStore
import app.solstone.platform.persistence.room.ImportDao
import app.solstone.platform.persistence.room.ImportDeliveryRow
import app.solstone.platform.persistence.room.ImportItemRow
import app.solstone.platform.persistence.room.ImportRecentReceiptRow
import app.solstone.platform.persistence.room.SourceIntakeRow
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumableImportRunnerTest {
    private class MemoryDao : ImportDao {
        val rows = ConcurrentHashMap<String, ImportItemRow>()
        val recent = ConcurrentHashMap<String, ImportRecentReceiptRow>()
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

    private val fakeProtector = object : SecretProtector {
        override fun protect(plaintext: ByteArray) = plaintext
        override fun unprotect(wrapped: ByteArray) = wrapped
    }

    private fun testStores(baseDir: Path): SyncStores {
        val dir = baseDir.resolve("stores").toFile().apply { mkdirs() }
        val identFile = File(dir, "identity.tsv")
        val credFile = File(dir, "credential.pem")
        val epFile = File(dir, "endpoint.txt")
        val commitFile = File(dir, "pairing.commit")
        val confirmFile = File(dir, "journal_confirmation.json")
        val jvFile = File(dir, "journal_version.tsv")
        val jmFile = File(dir, "journal_mark.json")
        val pushKeyFile = File(dir, "push-key.bin")

        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = fakeProtector,
            pushKeyFile = pushKeyFile,
            pushKeyProtector = fakeProtector,
        )
        val home = PairedHome(
            instanceId = "home-1",
            homeLabel = "Home",
            relayOrigin = null,
            caChainFingerprint = "sha256:ca-1",
            clientCertFingerprint = "sha256:cert-1",
            observerHandle = "obs",
            deviceToken = null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        val cred = ClientCredential(
            privateKeyPem = "-----BEGIN PRIVATE KEY-----\nkey-1\n-----END PRIVATE KEY-----\n",
            clientCertPem = "-----BEGIN CERTIFICATE-----\ncert-1\n-----END CERTIFICATE-----\n",
            caChainPem = listOf("-----BEGIN CERTIFICATE-----\nca-1\n-----END CERTIFICATE-----\n"),
        )
        graph.installOrReplace(home, cred, DirectEndpoint("10.0.0.1", 7657), true)

        val mutator = PublisherIdentityMutatorAdapter(graph)
        val jvStore = FileJournalVersionStore(jvFile).apply {
            save(JournalVersionRecord("home-1", "sha256:ca-1", "2.1.0"))
        }
        val jmStore = FileJournalMarkStore(jmFile)
        val confirmStore = FileJournalConfirmationStore(confirmFile).apply {
            confirm("sha256:cert-1")
        }

        return SyncStores(
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
    }

    @Test
    fun retryLoopUsesChunkRetryLadderResetsAfterProgressAndResumesWithoutResendingChunks() {
        val root = Files.createTempDirectory("runner-retry-test")
        try {
            val stores = testStores(root)
            val dao = MemoryDao()
            val importRoot = root.resolve("imports").also { Files.createDirectories(it) }
            val store = ImportCustodyStore(root = importRoot, dao = dao, clock = { 10_000L })
            val advertised = 512L * 1024L
            val recovered = 256L * 1024L
            val payload = ByteArray((3 * advertised).toInt()) { ((it * 13) % 251).toByte() }
            val id = store.begin("file")!!
            Files.write(importRoot.resolve(".staging").resolve(id).resolve("payload"), payload)
            assertTrue(store.publish(id, "archive.bin", "application/octet-stream", payload.size.toLong()))

            val serverData = ByteArrayOutputStream()
            val putOffsets = mutableListOf<Long>()
            var commitSha256: String? = null
            val waits = mutableListOf<Long>()
            val dialCount = AtomicInteger(0)

            val control = ResumableImportSessionControl()
            val outcome = runResumableImportUploadLoop(
                stores = stores,
                importStore = store,
                control = control,
                jitterUnit = { 0.5 },
                waitRetry = {
                    check(dialCount.get() < 10) { "retry loop did not finish" }
                    waits += it
                },
                openClient = { _, _ ->
                    val dial = dialCount.incrementAndGet()
                    // Dials 1 & 2 fail before any progress (1s, then 2s backoff).
                    if (dial <= 2) throw IOException("offline-$dial")
                    // Dial 3 delivers the first piece then drops before the second -> progress resets backoff!
                    // Dial 4 fails right away -> must use fresh 1s backoff (NOT 5s!).
                    if (dial == 4) throw IOException("transient-after-progress")
                    object : PlHttpClient, Closeable {
                        override fun request(
                            method: String,
                            path: String,
                            headers: Map<String, String>,
                            body: ByteArray?,
                            maxResponseBytes: Int,
                        ): HttpResponse = when {
                            method == "GET" && path == "/app/network/api/status" -> HttpResponse(200, emptyMap(), ByteArray(0))
                            method == "GET" && path == "/api/system/status" -> HttpResponse(200, emptyMap(), """{"capabilities":["resumable_import_uploads"]}""".toByteArray())
                            method == "GET" && path == "/app/import/api/sources" -> HttpResponse(200, emptyMap(), """{"sources":[],"resumable":{"threshold_bytes":500,"chunk_bytes":$advertised,"max_file_bytes":${payload.size}}}""".toByteArray())
                            method == "POST" && path == "/app/import/api/uploads" -> HttpResponse(201, emptyMap(), """{"status":"open","upload_id":"upl_run","next_offset":0,"total_bytes":${payload.size},"chunk_bytes":$advertised}""".toByteArray())
                            method == "GET" && path == "/app/import/api/uploads/upl_run" -> HttpResponse(200, emptyMap(), """{"status":"open","upload_id":"upl_run","next_offset":${serverData.size()},"total_bytes":${payload.size},"chunk_bytes":$advertised,"phase":"sending"}""".toByteArray())
                            method == "POST" && path == "/app/import/api/uploads/upl_run/commit" -> {
                                commitSha256 = (parseJson(body!!.toString(Charsets.UTF_8)) as Map<*, *>)["sha256"] as String
                                HttpResponse(200, emptyMap(), """{"status":"staged","path":"/imports/20261125_120001/archive.bin","timestamp":"20261125_120001","replay":false}""".toByteArray())
                            }
                            method == "POST" && path == "/app/import/api/start" -> HttpResponse(200, emptyMap(), """{"status":"ok","task_id":"t1","import_id":"20261125_120001"}""".toByteArray())
                            else -> error("unexpected $method $path")
                        }

                        override fun requestStreaming(
                            method: String,
                            path: String,
                            headers: Map<String, String>,
                            body: StreamingBody,
                            maxResponseBytes: Int,
                        ): HttpResponse {
                            val offset = path.substringAfter("?offset=").toLong()
                            putOffsets += offset
                            if (dial == 3 && offset == advertised) {
                                body.close()
                                throw IOException("dropped mid-transfer")
                            }
                            val buf = ByteArray(1024)
                            body.use {
                                while (true) {
                                    val n = it.readChunk(buf, 0, buf.size)
                                    if (n < 0) break
                                    if (n > 0) serverData.write(buf, 0, n)
                                }
                            }
                            val next = serverData.size().toLong()
                            return HttpResponse(200, emptyMap(), """{"upload_id":"upl_run","next_offset":$next,"total_bytes":${payload.size},"complete":${next >= payload.size}}""".toByteArray())
                        }

                        override fun close() = Unit
                    }
                },
            )

            assertEquals(ResumableImportRunOutcome.FINISHED, outcome)
            assertNull(dao.byId(id))
            assertEquals(bytesToLowerHex(MessageDigest.getInstance("SHA-256").digest(serverData.toByteArray())), commitSha256)
            // Wait ladder: 1s (dial 1), 2s (dial 2), 0ms immediate retry after dial 3 progress, 1s (dial 4)!
            assertEquals(listOf(1_000L, 2_000L, 1_000L), waits)
            assertEquals(listOf(0L, advertised, advertised, advertised + recovered), putOffsets.take(4))
            assertTrue(putOffsets.drop(2).zipWithNext().all { (previous, next) -> next > previous })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun networkChangeInterruptsWedgedClientAndStopReturnsRescheduleAndRespectsAgeGate() {
        val root = Files.createTempDirectory("runner-net-test")
        try {
            val stores = testStores(root)
            val dao = MemoryDao()
            val importRoot = root.resolve("imports").also { Files.createDirectories(it) }
            val store = ImportCustodyStore(root = importRoot, dao = dao, clock = { 10_000L })
            val payload = ByteArray(700) { it.toByte() }
            val id = store.begin("file")!!
            Files.write(importRoot.resolve(".staging").resolve(id).resolve("payload"), payload)
            assertTrue(store.publish(id, "net.bin", "application/octet-stream", payload.size.toLong()))

            // 1. Closed age gate returns FINISHED immediately without dialing.
            val prevClosed = SyncDrainGate.closed
            var dialedWhenClosed = false
            try {
                SyncDrainGate.closed = { true }
                val res = runResumableImportUploadLoop(
                    stores = stores,
                    importStore = store,
                    control = ResumableImportSessionControl(),
                    openClient = { _, _ -> dialedWhenClosed = true; error("must not dial") },
                )
                assertEquals(ResumableImportRunOutcome.FINISHED, res)
                assertFalse(dialedWhenClosed)
            } finally {
                SyncDrainGate.closed = prevClosed
            }

            // 2. Active socket closed on signalNetworkChange(true), and requestStop yields RESCHEDULE.
            val wedgedEntered = CountDownLatch(1)
            val closedSignal = CountDownLatch(1)
            val stoppedAfterWake = AtomicBoolean(false)
            val control = ResumableImportSessionControl()
            var outcome: ResumableImportRunOutcome? = null

            val worker = thread(name = "test-uidt-runner") {
                outcome = runResumableImportUploadLoop(
                    stores = stores,
                    importStore = store,
                    control = control,
                    openClient = { _, _ ->
                        if (stoppedAfterWake.get()) {
                            control.requestStop()
                            throw IOException("stopped")
                        }
                        object : PlHttpClient, Closeable {
                            override fun request(
                                method: String,
                                path: String,
                                headers: Map<String, String>,
                                body: ByteArray?,
                                maxResponseBytes: Int,
                            ): HttpResponse {
                                wedgedEntered.countDown()
                                assertTrue(closedSignal.await(5, TimeUnit.SECONDS))
                                throw IOException("socket closed on network switch")
                            }

                            override fun close() {
                                stoppedAfterWake.set(true)
                                closedSignal.countDown()
                            }
                        }
                    },
                )
            }
            assertTrue(wedgedEntered.await(5, TimeUnit.SECONDS))
            control.signalNetworkChange(interruptActiveConnection = true)
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertEquals(ResumableImportRunOutcome.RESCHEDULE, outcome)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
