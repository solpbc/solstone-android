// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.observer.ResumableConfigOutcome
import app.solstone.core.observer.ResumableUploadConfig
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.StreamingBody
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import app.solstone.platform.persistence.room.IMPORT_STATE_ATTENTION
import app.solstone.platform.persistence.room.ImportAttentionScope
import app.solstone.platform.persistence.room.ImportCustodyStore
import app.solstone.platform.persistence.room.ImportDao
import app.solstone.platform.persistence.room.ImportDeliveryRow
import app.solstone.platform.persistence.room.ImportItemRow
import app.solstone.platform.persistence.room.ImportRecentReceiptRow
import app.solstone.platform.persistence.room.SourceIntakeRow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumableImportDeliveryTest {
    private class MemoryImportDao : ImportDao {
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
        override fun recentReceipts(instanceId: String, cutoff: Long, limit: Int): List<ImportRecentReceiptRow> =
            recent.values.filter { it.instanceId == instanceId && it.deliveredAt >= cutoff }.take(limit)
        override fun pruneExpiredReceipts(cutoff: Long): Int = 0
        override fun trimRecentReceipts(instanceId: String, keep: Int): Int = 0
        override fun intakeBySource(sourceId: String): SourceIntakeRow? = null
        override fun upsertIntake(row: SourceIntakeRow) = Unit
    }

    private data class UploadSession(
        val uploadId: String,
        val clientItemId: String,
        val filename: String,
        val totalBytes: Long,
        val declaredSha256: String?,
        val data: ByteArrayOutputStream = ByteArrayOutputStream(),
    )

    private class FakeResumableJournal(
        var advertiseResumable: Boolean = true,
        var thresholdBytes: Long = 1024L,
        var chunkBytes: Long = 300L,
        var maxFileBytes: Long = 4L * 1024L * 1024L * 1024L,
    ) : PlHttpClient {
        val calls = mutableListOf<Pair<String, String>>()
        val putOffsets = mutableListOf<Long>()
        val putChunkShas = mutableListOf<String?>()
        val deletedUploadIds = mutableListOf<String>()
        val sessions = linkedMapOf<String, UploadSession>()
        val committedBytes = linkedMapOf<String, ByteArray>()
        val stagedByClientItem = linkedMapOf<String, Pair<String, String>>()
        private var seq = 0

        var onCreateOverride: ((Map<*, *>) -> HttpResponse?)? = null
        var onGetStatusOverride: ((String) -> HttpResponse?)? = null
        var onPutChunkOverride: ((uploadId: String, offset: Long, bytes: ByteArray, chunkSha: String?) -> HttpResponse?)? = null
        var onCommitOverride: ((String) -> HttpResponse?)? = null

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse {
            calls += method to path
            return when {
                method == "GET" && path == "/api/system/status" -> {
                    val caps = if (advertiseResumable) """["resumable_import_uploads"]""" else "[]"
                    json(200, """{"capabilities":$caps}""")
                }
                method == "GET" && path == "/app/import/api/sources" -> {
                    if (!advertiseResumable) {
                        json(200, """{"sources":[]}""")
                    } else {
                        json(
                            200,
                            """{"sources":[],"resumable":{"threshold_bytes":$thresholdBytes,"chunk_bytes":$chunkBytes,"max_file_bytes":$maxFileBytes}}""",
                        )
                    }
                }
                method == "POST" && path == "/app/import/api/uploads" -> {
                    val req = parseJson(body?.toString(Charsets.UTF_8) ?: "{}") as Map<*, *>
                    onCreateOverride?.invoke(req)?.let { return it }
                    val clientItemId = req["client_item_id"] as String
                    stagedByClientItem[clientItemId]?.let { (stagedPath, ts) ->
                        return json(
                            200,
                            toJson(
                                mapOf(
                                    "status" to "already_staged",
                                    "replay" to true,
                                    "save_response" to mapOf("status" to "staged", "path" to stagedPath, "timestamp" to ts, "replay" to true),
                                ),
                            ),
                        )
                    }
                    val existing = sessions.values.firstOrNull { it.clientItemId == clientItemId }
                    if (existing != null) {
                        return json(
                            200,
                            """{"status":"open","upload_id":"${existing.uploadId}","next_offset":${existing.data.size()},"total_bytes":${existing.totalBytes},"chunk_bytes":$chunkBytes,"expires_at":"2026-12-02T00:00:00Z"}""",
                        )
                    }
                    val uploadId = "upl_${++seq}"
                    val total = (req["total_bytes"] as java.math.BigDecimal).longValueExact()
                    val session = UploadSession(
                        uploadId = uploadId,
                        clientItemId = clientItemId,
                        filename = req["filename"] as String,
                        totalBytes = total,
                        declaredSha256 = req["sha256"] as? String,
                    )
                    sessions[uploadId] = session
                    json(
                        201,
                        """{"status":"open","upload_id":"$uploadId","next_offset":0,"total_bytes":$total,"chunk_bytes":$chunkBytes,"expires_at":"2026-12-02T00:00:00Z"}""",
                    )
                }
                method == "GET" && path.startsWith("/app/import/api/uploads/") -> {
                    val uploadId = path.removePrefix("/app/import/api/uploads/")
                    onGetStatusOverride?.invoke(uploadId)?.let { return it }
                    val session = sessions[uploadId] ?: return json(404, """{"error":"upload not found","reason_code":"upload_not_found"}""")
                    json(
                        200,
                        """{"status":"open","upload_id":"$uploadId","next_offset":${session.data.size()},"total_bytes":${session.totalBytes},"chunk_bytes":$chunkBytes,"phase":"sending","expires_at":"2026-12-02T00:00:00Z"}""",
                    )
                }
                method == "POST" && path.startsWith("/app/import/api/uploads/") && path.endsWith("/commit") -> {
                    val uploadId = path.removePrefix("/app/import/api/uploads/").removeSuffix("/commit")
                    onCommitOverride?.invoke(uploadId)?.let { return it }
                    val session = sessions[uploadId] ?: return json(404, """{"error":"upload not found","reason_code":"upload_not_found"}""")
                    val assembled = session.data.toByteArray()
                    if (assembled.size.toLong() != session.totalBytes) {
                        return json(409, """{"error":"incomplete","reason_code":"incomplete_upload","next_offset":${assembled.size}}""")
                    }
                    val actualSha = sha256(assembled)
                    val req = parseJson(body?.toString(Charsets.UTF_8) ?: "{}") as? Map<*, *>
                    val expectedSha = (req?.get("sha256") as? String) ?: session.declaredSha256
                    if (expectedSha != null && expectedSha != actualSha) {
                        sessions.remove(uploadId)
                        return json(422, """{"error":"checksum mismatch","reason_code":"whole_file_checksum_mismatch"}""")
                    }
                    sessions.remove(uploadId)
                    committedBytes[session.clientItemId] = assembled
                    val ts = "20261125_12000$seq"
                    val stagedPath = "/imports/$ts/${session.filename}"
                    stagedByClientItem[session.clientItemId] = stagedPath to ts
                    json(200, """{"status":"staged","path":"$stagedPath","timestamp":"$ts","replay":false}""")
                }
                method == "DELETE" && path.startsWith("/app/import/api/uploads/") -> {
                    val uploadId = path.removePrefix("/app/import/api/uploads/")
                    deletedUploadIds += uploadId
                    sessions.remove(uploadId)
                    json(200, """{"status":"aborted","upload_id":"$uploadId"}""")
                }
                method == "POST" && path == "/app/import/api/save" -> {
                    val ts = "20261125_11000${++seq}"
                    json(200, """{"status":"staged","path":"/imports/$ts/small.bin","timestamp":"$ts","replay":false}""")
                }
                method == "POST" && path == "/app/import/api/start" -> {
                    json(200, """{"status":"ok","task_id":"task-1","import_id":"20261125_120001"}""")
                }
                else -> error("unexpected request $method $path")
            }
        }

        override fun requestStreaming(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: StreamingBody,
            maxResponseBytes: Int,
        ): HttpResponse {
            calls += method to path
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(1024)
            body.use {
                while (true) {
                    val n = it.readChunk(chunk, 0, chunk.size)
                    if (n < 0) break
                    if (n > 0) buf.write(chunk, 0, n)
                }
            }
            val bytes = buf.toByteArray()
            assertEquals(body.contentLength, bytes.size.toLong(), "exact Content-Length must match streamed bytes")
            if (method == "POST" && path == "/app/import/api/save") {
                val ts = "20261125_11000${++seq}"
                return json(200, """{"status":"staged","path":"/imports/$ts/small.bin","timestamp":"$ts","replay":false}""")
            }
            if (method == "PUT" && path.startsWith("/app/import/api/uploads/")) {
                val rest = path.removePrefix("/app/import/api/uploads/")
                val uploadId = rest.substringBefore("?offset=")
                val offset = rest.substringAfter("?offset=").toLong()
                val chunkSha = headers["X-Chunk-Sha256"]
                putOffsets += offset
                putChunkShas += chunkSha
                onPutChunkOverride?.invoke(uploadId, offset, bytes, chunkSha)?.let { return it }
                val session = sessions[uploadId] ?: return json(404, """{"error":"upload not found","reason_code":"upload_not_found"}""")
                val expectedOffset = session.data.size().toLong()
                if (offset != expectedOffset) {
                    return json(409, """{"error":"offset mismatch","reason_code":"offset_mismatch","next_offset":$expectedOffset}""")
                }
                if (chunkSha != null && chunkSha != sha256(bytes)) {
                    return json(422, """{"error":"chunk mismatch","reason_code":"chunk_checksum_mismatch","next_offset":$expectedOffset}""")
                }
                session.data.write(bytes)
                val nextOffset = session.data.size().toLong()
                val complete = nextOffset >= session.totalBytes
                return json(
                    200,
                    """{"upload_id":"$uploadId","next_offset":$nextOffset,"total_bytes":${session.totalBytes},"complete":$complete,"expires_at":"2026-12-02T00:00:00Z"}""",
                )
            }
            error("unexpected streaming request $method $path")
        }

        private fun json(status: Int, body: String) =
            HttpResponse(status, mapOf("content-type" to "application/json"), body.toByteArray())

        private fun sha256(bytes: ByteArray): String =
            bytesToLowerHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun withStore(block: (Path, MemoryImportDao, ImportCustodyStore) -> Unit) {
        val root = Files.createTempDirectory("resumable-delivery-test")
        try {
            val dao = MemoryImportDao()
            var clock = 10_000L
            val store = ImportCustodyStore(root = root, dao = dao, clock = { ++clock })
            block(root, dao, store)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun enqueueFile(root: Path, store: ImportCustodyStore, name: String, bytes: ByteArray): String {
        val id = store.begin("file")!!
        Files.write(root.resolve(".staging").resolve(id).resolve("payload"), bytes)
        assertTrue(store.publish(id, name, "application/octet-stream", bytes.size.toLong()))
        return id
    }

    @Test
    fun thresholdBoundarySendsSmallFileViaSaveAndLargeFileViaResumableChunks() = withStore { root, dao, store ->
        val smallBytes = ByteArray(1023) { (it % 251).toByte() }
        val largeBytes = ByteArray(1024) { ((it * 31) % 251).toByte() }
        val smallId = enqueueFile(root, store, "small.bin", smallBytes)
        val largeId = enqueueFile(root, store, "large.bin", largeBytes)

        val journal = FakeResumableJournal(thresholdBytes = 1024L, chunkBytes = 300L)
        var observedThreshold: Long? = null
        val result = deliverImports(
            http = journal,
            store = store,
            run = ImportRun(),
            instanceId = "inst-1",
            journalVersion = "2.1.0",
            gate = ImportVersionGate.SUPPORTED,
            pairingCurrent = { true },
            now = { 20_000L },
            log = { _, _ -> },
            onObservedThreshold = { observedThreshold = it },
        )
        assertEquals(ImportStageResult.CLEAN, result)
        assertEquals(1024L, observedThreshold)
        assertNull(dao.byId(smallId))
        assertNull(dao.byId(largeId))

        // Capability + sources probed only once per connection.
        assertEquals(1, journal.calls.count { it == ("GET" to "/api/system/status") })
        assertEquals(1, journal.calls.count { it == ("GET" to "/app/import/api/sources") })
        // Small file used /save; large file used 4 resumable chunks (300, 300, 300, 124) + commit + start.
        assertEquals(1, journal.calls.count { it == ("POST" to "/app/import/api/save") })
        assertEquals(listOf(0L, 300L, 600L, 900L), journal.putOffsets)
        assertTrue(journal.putChunkShas.all { it != null && it.length == 64 })
        assertContentEquals(largeBytes, journal.committedBytes[largeId])
    }

    @Test
    fun olderJournalWithoutCapabilityAndUnprobed404Or405FallBackToSave() = withStore { root, dao, store ->
        val bytes = ByteArray(2048) { it.toByte() }

        // 1. Journal without resumable_import_uploads capability -> /save unchanged.
        val id1 = enqueueFile(root, store, "old-journal.bin", bytes)
        val oldJournal = FakeResumableJournal(advertiseResumable = false)
        assertEquals(
            ImportStageResult.CLEAN,
            deliverImports(oldJournal, store, ImportRun(), "inst-1", "2.0.99", ImportVersionGate.SUPPORTED, { true }, { 20_000L }, { _, _ -> }),
        )
        assertNull(dao.byId(id1))
        assertTrue(oldJournal.calls.none { it.second.startsWith("/app/import/api/uploads") })
        assertEquals(1, oldJournal.calls.count { it == ("POST" to "/app/import/api/save") })

        // 2. Unprobed 404 and 405 on POST /uploads fall back identically to /save.
        for (status in listOf(404, 405)) {
            val id = enqueueFile(root, store, "fallback-$status.bin", bytes)
            val fallbackJournal = FakeResumableJournal(thresholdBytes = 512L, chunkBytes = 256L).apply {
                onCreateOverride = { HttpResponse(status, emptyMap(), ByteArray(0)) }
            }
            val result = deliverImports(
                http = fallbackJournal,
                store = store,
                run = ImportRun(),
                instanceId = "inst-1",
                journalVersion = "2.1.0",
                gate = ImportVersionGate.SUPPORTED,
                pairingCurrent = { true },
                now = { 20_000L },
                log = { _, _ -> },
                resumableConfigProvider = { ResumableConfigOutcome.Supported(ResumableUploadConfig(512L, 256L, 10_000L)) },
            )
            assertEquals(ImportStageResult.CLEAN, result)
            assertNull(dao.byId(id))
            assertEquals(1, fallbackJournal.calls.count { it == ("POST" to "/app/import/api/save") })
        }
    }

    @Test
    fun interruptedUploadResyncsViaGetAndNeverResendsConfirmedChunks() = withStore { root, dao, store ->
        val piece = 300 * 1024L
        val payload = ByteArray((3 * piece + 100).toInt()) { ((it * 17) % 253).toByte() }
        val id = enqueueFile(root, store, "resume.bin", payload)
        val journal = FakeResumableJournal(thresholdBytes = 512L, chunkBytes = piece)

        // The journal accepts the second piece before the connection drops.
        var injectedAmbiguousDrop = false
        journal.onPutChunkOverride = { uploadId, offset, bytes, _ ->
            if (offset == piece && !injectedAmbiguousDrop) {
                injectedAmbiguousDrop = true
                journal.sessions[uploadId]!!.data.write(bytes)
                throw IOException("relay restarted before 200")
            }
            null
        }

        val firstRun = ImportRun()
        assertEquals(
            ImportStageResult.RETRY,
            deliverImports(journal, store, firstRun, "inst-1", "2.1.0", ImportVersionGate.SUPPORTED, { true }, { 20_000L }, { _, _ -> }),
        )
        // Client locally only saw one piece confirmed, while the server actually confirmed two.
        assertEquals(piece, store.item(id)!!.uploadOffset)
        assertEquals("paused", store.items("inst-1", "2.1.0").single().uploadPhase)

        // GET confirms two pieces. The second run keeps the advertised size.
        val secondRun = ImportRun(retried = firstRun.retried)
        assertEquals(
            ImportStageResult.CLEAN,
            deliverImports(journal, store, secondRun, "inst-1", "2.1.0", ImportVersionGate.SUPPORTED, { true }, { 25_000L }, { _, _ -> }),
        )
        assertNull(dao.byId(id))
        assertEquals(listOf(0L, piece, piece * 2, piece * 3), journal.putOffsets, "zero completed chunks may be re-sent")
        assertTrue(journal.calls.any { it.first == "GET" && it.second.startsWith("/app/import/api/uploads/") })
        assertContentEquals(payload, journal.committedBytes[id])
    }

    @Test
    fun retryWithNoConfirmedProgressUsesSmallerPutsOnlyForThatUpload() = withStore { root, dao, store ->
        val advertised = 1024 * 1024
        val fallback = 256 * 1024
        val bytes = ByteArray(advertised + 1) { it.toByte() }
        enqueueFile(root, store, "interrupted.bin", bytes)
        val journal = FakeResumableJournal(thresholdBytes = 500L, chunkBytes = advertised.toLong())
        val sizes = mutableListOf<Int>()
        journal.onPutChunkOverride = { _, _, chunk, _ ->
            sizes += chunk.size
            if (sizes.size == 1) throw java.net.SocketTimeoutException("no receipt")
            null
        }
        fun deliver(custody: ImportCustodyStore, run: ImportRun) =
            deliverImports(journal, custody, run, "inst-1", "2.1.0", ImportVersionGate.SUPPORTED, { true }, { 20_000L }, { _, _ -> })

        val first = ImportRun()
        assertEquals(ImportStageResult.RETRY, deliver(store, first))
        // WorkManager stores the failed item; a later process reopens the custody row.
        recordImportRetries(store, first, "inst-1", now = 0)
        val reopened = ImportCustodyStore(root, dao)
        assertEquals(ImportStageResult.CLEAN, deliver(reopened, ImportRun()))
        assertEquals(listOf(advertised, fallback, fallback, fallback, fallback, 1), sizes)
        val completed = sizes.size
        enqueueFile(root, store, "fresh.bin", bytes.copyOf().also { it[0] = 42 })
        assertEquals(ImportStageResult.CLEAN, deliver(store, ImportRun()))
        assertEquals(listOf(advertised, 1), sizes.drop(completed))
    }

    @Test
    fun offsetMismatchWithAndWithoutBodyResyncsCleanly() = withStore { root, dao, store ->
        val payload = ByteArray(900) { (it % 199).toByte() }
        val id = enqueueFile(root, store, "mismatch.bin", payload)
        val journal = FakeResumableJournal(thresholdBytes = 500L, chunkBytes = 300L)

        var bodyless409Injected = false
        journal.onPutChunkOverride = { uploadId, offset, _, _ ->
            if (offset == 0L && !bodyless409Injected) {
                bodyless409Injected = true
                // Pretend server already holds bytes 0..300 and intermediary dropped the 409 JSON body.
                journal.sessions[uploadId]!!.data.write(payload, 0, 300)
                HttpResponse(409, emptyMap(), ByteArray(0))
            } else {
                null
            }
        }

        assertEquals(
            ImportStageResult.CLEAN,
            deliverImports(journal, store, ImportRun(), "inst-1", "2.1.0", ImportVersionGate.SUPPORTED, { true }, { 20_000L }, { _, _ -> }),
        )
        assertNull(dao.byId(id))
        assertEquals(listOf(0L, 300L, 600L), journal.putOffsets)
        assertTrue(journal.calls.any { it.first == "GET" && it.second.startsWith("/app/import/api/uploads/") })
        assertContentEquals(payload, journal.committedBytes[id])
    }

    @Test
    fun chunkChecksumMismatchResendsChunkAndWholeFileMismatchSurfacesAttention() = withStore { root, dao, store ->
        val payload = ByteArray(700) { ((it * 7) % 251).toByte() }
        val id1 = enqueueFile(root, store, "chunk-retry.bin", payload)
        val journal1 = FakeResumableJournal(thresholdBytes = 500L, chunkBytes = 350L)
        var chunk422Once = false
        journal1.onPutChunkOverride = { _, offset, _, _ ->
            if (offset == 350L && !chunk422Once) {
                chunk422Once = true
                HttpResponse(422, emptyMap(), """{"error":"chunk corrupted","reason_code":"chunk_checksum_mismatch","next_offset":350}""".toByteArray())
            } else {
                null
            }
        }
        assertEquals(
            ImportStageResult.CLEAN,
            deliverImports(journal1, store, ImportRun(), "inst-1", "2.1.0", ImportVersionGate.SUPPORTED, { true }, { 20_000L }, { _, _ -> }),
        )
        assertNull(dao.byId(id1))
        assertEquals(listOf(0L, 350L, 350L), journal1.putOffsets)
        assertContentEquals(payload, journal1.committedBytes[id1])

        // Whole-file mismatch on commit -> needs attention, upload session cleared, local copy retained.
        val id2 = enqueueFile(root, store, "whole-mismatch.bin", payload)
        val journal2 = FakeResumableJournal(thresholdBytes = 500L, chunkBytes = 350L).apply {
            onCommitOverride = {
                HttpResponse(422, emptyMap(), """{"error":"whole file mismatch","reason_code":"whole_file_checksum_mismatch"}""".toByteArray())
            }
        }
        assertEquals(
            ImportStageResult.CLEAN,
            deliverImports(journal2, store, ImportRun(), "inst-1", "2.1.0", ImportVersionGate.SUPPORTED, { true }, { 20_000L }, { _, _ -> }),
        )
        val row = dao.byId(id2)!!
        assertEquals(IMPORT_STATE_ATTENTION, row.state)
        assertEquals("whole_file_checksum_mismatch", row.attentionReasonCode)
        assertNull(row.uploadId)
        assertEquals(0L, row.uploadOffset)
        assertTrue(Files.exists(store.item(id2)!!.payloadPath))
    }

    @Test
    fun journalFull507PreservesUploadSessionAndResumesAfterRetry() = withStore { root, dao, store ->
        val payload = ByteArray(900) { (it % 239).toByte() }
        val id = enqueueFile(root, store, "diskfull.bin", payload)
        val journal = FakeResumableJournal(thresholdBytes = 500L, chunkBytes = 300L)
        var fullOnce = true
        journal.onPutChunkOverride = { _, offset, _, _ ->
            if (offset == 300L && fullOnce) {
                fullOnce = false
                HttpResponse(507, emptyMap(), """{"error":"not enough disk space","reason_code":"insufficient_journal_space","required_bytes":900,"available_bytes":100}""".toByteArray())
            } else {
                null
            }
        }

        assertEquals(
            ImportStageResult.CLEAN,
            deliverImports(journal, store, ImportRun(), "inst-1", "2.1.0", ImportVersionGate.SUPPORTED, { true }, { 20_000L }, { _, _ -> }),
        )
        val attentionRow = dao.byId(id)!!
        assertEquals(IMPORT_STATE_ATTENTION, attentionRow.state)
        assertEquals("insufficient_journal_space", attentionRow.attentionReasonCode)
        assertNotNull(attentionRow.uploadId)
        assertEquals(300L, attentionRow.uploadOffset)

        // Owner frees disk space and retries -> resumes at offset 300 without re-sending offset 0.
        assertEquals(app.solstone.platform.persistence.room.ImportChange.DONE, store.retry(id))
        assertEquals(
            ImportStageResult.CLEAN,
            deliverImports(journal, store, ImportRun(), "inst-1", "2.1.0", ImportVersionGate.SUPPORTED, { true }, { 25_000L }, { _, _ -> }),
        )
        assertNull(dao.byId(id))
        assertEquals(listOf(0L, 300L, 300L, 600L), journal.putOffsets)
        assertContentEquals(payload, journal.committedBytes[id])
    }

    @Test
    fun expiredUpload404MarksAttentionWithoutFallbackToSaveAndRetryCreatesNewSession() = withStore { root, dao, store ->
        val payload = ByteArray(900) { (it % 173).toByte() }
        val id = enqueueFile(root, store, "expired.bin", payload)
        val journal = FakeResumableJournal(thresholdBytes = 500L, chunkBytes = 300L)

        // Simulate existing persisted upload session from 8 days ago that expired on journal.
        assertTrue(store.recordUploadProgress(id, "upl_expired", "inst-1", 300L))
        assertEquals(
            ImportStageResult.CLEAN,
            deliverImports(journal, store, ImportRun(), "inst-1", "2.1.0", ImportVersionGate.SUPPORTED, { true }, { 20_000L }, { _, _ -> }),
        )
        assertTrue(journal.calls.none { it == ("POST" to "/app/import/api/save") })
        val attentionRow = dao.byId(id)!!
        assertEquals(IMPORT_STATE_ATTENTION, attentionRow.state)
        assertEquals("upload_not_found", attentionRow.attentionReasonCode)
        assertNull(attentionRow.uploadId)

        // Owner retries -> creates a brand-new resumable session (NOT /save) and finishes cleanly.
        assertEquals(app.solstone.platform.persistence.room.ImportChange.DONE, store.retry(id))
        assertEquals(
            ImportStageResult.CLEAN,
            deliverImports(journal, store, ImportRun(), "inst-1", "2.1.0", ImportVersionGate.SUPPORTED, { true }, { 25_000L }, { _, _ -> }),
        )
        assertNull(dao.byId(id))
        assertTrue(journal.calls.none { it == ("POST" to "/app/import/api/save") })
        assertContentEquals(payload, journal.committedBytes[id])
    }

    @Test
    fun pairingSwitchClearsUploadSessionAndStartsCleanAtOffsetZeroOnNewJournal() = withStore { root, dao, store ->
        val payload = ByteArray(900) { (it % 131).toByte() }
        val id = enqueueFile(root, store, "switched.bin", payload)
        assertTrue(store.recordUploadProgress(id, "upl_journal_a", "inst-a", 600L))
        assertTrue(store.recordPayloadSha256(id, bytesToLowerHex(MessageDigest.getInstance("SHA-256").digest(payload))))

        val journalB = FakeResumableJournal(thresholdBytes = 500L, chunkBytes = 300L)
        assertEquals(
            ImportStageResult.CLEAN,
            deliverImports(journalB, store, ImportRun(), "inst-b", "2.1.0", ImportVersionGate.SUPPORTED, { true }, { 20_000L }, { _, _ -> }),
        )
        assertNull(dao.byId(id))
        // Must NEVER query Journal B with Journal A's upload_id.
        assertTrue(journalB.calls.none { it.second.contains("upl_journal_a") })
        assertEquals(listOf(0L, 300L, 600L), journalB.putOffsets)
        assertContentEquals(payload, journalB.committedBytes[id])
    }

    @Test
    fun cancelMidUploadAbortsRemoteSessionAndDeletesLocalCopy() = withStore { root, dao, store ->
        val payload = ByteArray(900) { (it % 109).toByte() }
        val id = enqueueFile(root, store, "cancelme.bin", payload)
        val journal = FakeResumableJournal(thresholdBytes = 500L, chunkBytes = 300L)

        journal.onPutChunkOverride = { uploadId, offset, bytes, _ ->
            if (offset == 0L) {
                journal.sessions[uploadId]!!.data.write(bytes)
                // Owner taps Cancel right as chunk 0 completes!
                store.requestCancel(id)
                HttpResponse(
                    200,
                    emptyMap(),
                    """{"upload_id":"$uploadId","next_offset":300,"total_bytes":900,"complete":false}""".toByteArray(),
                )
            } else {
                null
            }
        }

        assertEquals(
            ImportStageResult.CLEAN,
            deliverImports(journal, store, ImportRun(), "inst-1", "2.1.0", ImportVersionGate.SUPPORTED, { true }, { 20_000L }, { _, _ -> }),
        )
        assertNull(dao.byId(id))
        assertFalse(Files.exists(root.resolve(id)))
        assertEquals(listOf(0L), journal.putOffsets)
        assertEquals(listOf("upl_1"), journal.deletedUploadIds)
    }

    @Test
    fun workManagerTimeBudgetYieldsCleanlyBetweenChunksAndFollowUpCompletes() = withStore { root, dao, store ->
        val payload = ByteArray(900) { (it % 97).toByte() }
        val id = enqueueFile(root, store, "budget.bin", payload)
        val journal = FakeResumableJournal(thresholdBytes = 500L, chunkBytes = 300L)
        var chunksSentInWindow = 0

        val firstRun = ImportRun()
        val firstResult = deliverImports(
            http = journal,
            store = store,
            run = firstRun,
            instanceId = "inst-1",
            journalVersion = "2.1.0",
            gate = ImportVersionGate.SUPPORTED,
            pairingCurrent = { true },
            now = { 20_000L },
            log = { _, _ -> },
            shouldYield = {
                // Allow first chunk (offset 0 -> 300), then signal time budget reached.
                chunksSentInWindow = journal.putOffsets.size
                chunksSentInWindow >= 1
            },
        )
        assertEquals(ImportStageResult.CLEAN, firstResult)
        assertTrue(firstRun.yieldedByBudget)
        assertTrue(firstRun.stageClean)
        assertTrue(firstRun.retried.isEmpty(), "time-budget yield must not incur retry backoff")
        assertEquals(300L, store.item(id)!!.uploadOffset)
        assertEquals("paused", store.items("inst-1", "2.1.0").single().uploadPhase)

        // Follow-up window picks up cleanly from offset 300 without re-sending offset 0.
        val secondResult = deliverImports(
            http = journal,
            store = store,
            run = ImportRun(),
            instanceId = "inst-1",
            journalVersion = "2.1.0",
            gate = ImportVersionGate.SUPPORTED,
            pairingCurrent = { true },
            now = { 21_000L },
            log = { _, _ -> },
        )
        assertEquals(ImportStageResult.CLEAN, secondResult)
        assertNull(dao.byId(id))
        assertEquals(listOf(0L, 300L, 600L), journal.putOffsets)
        assertContentEquals(payload, journal.committedBytes[id])
    }

    @Test
    fun unreadableLocalCustodyFileBecomesLocalAttention() = withStore { root, dao, store ->
        val payload = ByteArray(700) { 42 }
        val id = enqueueFile(root, store, "vanished.bin", payload)
        Files.delete(store.item(id)!!.payloadPath)

        val journal = FakeResumableJournal(thresholdBytes = 500L, chunkBytes = 300L)
        assertEquals(
            ImportStageResult.CLEAN,
            deliverImports(journal, store, ImportRun(), "inst-1", "2.1.0", ImportVersionGate.SUPPORTED, { true }, { 20_000L }, { _, _ -> }),
        )
        val row = dao.byId(id)!!
        assertEquals(IMPORT_STATE_ATTENTION, row.state)
        assertEquals("missing_copy", row.attentionKind)
        assertEquals(ImportAttentionScope.LOCAL, store.items("inst-1", "2.1.0").single().scope)
    }

    @Test
    fun thresholdHintStorePersistsPerInstanceAndIgnoresOtherInstances() = withStore { root, _, _ ->
        val hintStore = ResumableThresholdHintStore(root.toFile())
        assertNull(hintStore.readFor("inst-a"))
        hintStore.record("inst-a", 16L * 1024L * 1024L)
        assertEquals(16L * 1024L * 1024L, hintStore.readFor("inst-a"))
        assertNull(hintStore.readFor("inst-b"))
        hintStore.record("inst-b", 4L * 1024L * 1024L)
        assertEquals(4L * 1024L * 1024L, hintStore.readFor("inst-b"))
        assertNull(hintStore.readFor("inst-a"))
    }
}
