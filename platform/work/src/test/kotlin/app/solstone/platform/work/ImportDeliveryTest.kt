// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.platform.persistence.room.ImportCustodyStore
import app.solstone.platform.persistence.room.ImportDao
import app.solstone.platform.persistence.room.ImportDeliveryRow
import app.solstone.platform.persistence.room.ImportItemRow
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportDeliveryTest {
    private class Dao : ImportDao {
        val rows = ConcurrentHashMap<String, ImportItemRow>()
        var delivery: ImportDeliveryRow? = null
        override fun insert(row: ImportItemRow) { rows[row.id] = row }
        override fun update(row: ImportItemRow): Int = if (rows.containsKey(row.id)) { rows[row.id] = row; 1 } else 0
        override fun delete(id: String): Int = if (rows.remove(id) != null) 1 else 0
        override fun byId(id: String): ImportItemRow? = rows[id]
        override fun all(): List<ImportItemRow> = rows.values.sortedWith(compareBy<ImportItemRow> { it.createdAt }.thenBy { it.id })
        override fun putDelivery(row: ImportDeliveryRow) { delivery = row }
        override fun delivery(): ImportDeliveryRow? = delivery
    }

    /** Answers in order; an [IOException] in the script is thrown instead of answered. */
    private class Journal(vararg script: Any) : PlHttpClient {
        private val answers = ArrayDeque(script.toList())
        val paths = mutableListOf<String>()
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Int): HttpResponse {
            paths += path
            return when (val next = answers.removeFirst()) {
                is IOException -> throw next
                is HttpResponse -> next
                else -> error("bad script")
            }
        }
    }

    private val save = "/app/import/api/save"
    private val start = "/app/import/api/start"
    private fun saved(ts: String = "20261019_101500") = json(200, """{"status":"staged","path":"/imports/$ts/doc.pdf","timestamp":"$ts","replay":false}""")
    private fun started() = json(200, """{"status":"ok","task_id":"t1","import_id":"20261019_101500"}""")
    private fun alreadyImported() = json(400, """{"error":"already imported","reason_code":"invalid_operation_for_state","kind":"already_imported"}""")
    private fun refused413() = json(413, """{"error":"that file is too large","reason_code":"multipart_part_too_large"}""")
    private fun json(status: Int, body: String) = HttpResponse(status, mapOf("content-type" to "application/json"), body.toByteArray())

    private lateinit var root: Path
    private lateinit var dao: Dao
    private lateinit var store: ImportCustodyStore

    private fun setUp(): ImportCustodyStore {
        root = Files.createTempDirectory("import-delivery")
        dao = Dao()
        store = ImportCustodyStore(root = root, dao = dao, clock = { 1_000L })
        return store
    }

    private fun share(name: String = "doc.pdf"): String {
        val id = store.begin("file")!!
        val bytes = "pdf-bytes-$name".toByteArray()
        Files.write(root.resolve(".staging").resolve(id).resolve("payload"), bytes)
        assertTrue(store.publish(id, name, "application/pdf", bytes.size.toLong()))
        return id
    }

    private fun deliver(
        journal: PlHttpClient,
        run: ImportRun = ImportRun(),
        instance: String = "A",
        version: String = "2.0.52",
        gate: ImportVersionGate = ImportVersionGate.SUPPORTED,
        pairingCurrent: () -> Boolean = { true },
        diag: (String) -> Unit = {},
    ): ImportStageResult = deliverImports(journal, store, run, instance, version, gate, pairingCurrent, { 2_000L }, { _, _ -> }, diag)

    @Test
    fun aSharedFileIsSavedThenStartedAndItsCopyRemovedOnlyAfter() {
        setUp()
        val id = share()
        val journal = Journal(saved(), started())
        assertEquals(ImportStageResult.CLEAN, deliver(journal))
        assertEquals(listOf(save, start), journal.paths)
        assertNull(dao.byId(id))
        assertFalse(Files.exists(root.resolve(id)))
        assertEquals("A", dao.delivery?.instanceId)
    }

    @Test
    fun contentTheJournalAlreadyHasFinishesWithoutAStart() {
        setUp()
        val id = share()
        val journal = Journal(alreadyImported())
        assertEquals(ImportStageResult.CLEAN, deliver(journal))
        assertEquals(listOf(save), journal.paths)
        assertNull(dao.byId(id))
    }

    @Test
    fun aTimedOutStartLeavesTheItemSavedAndTheNextRunReplaysIt() {
        setUp()
        val id = share()
        val run = ImportRun()
        assertEquals(ImportStageResult.RETRY, deliver(Journal(saved(), SocketTimeoutException("read timed out")), run))
        recordImportRetries(store, run, "A", 2_000L)
        assertNotNull(dao.byId(id))
        assertEquals("20261019_101500", dao.byId(id)!!.progressTimestamp)
        assertTrue(Files.exists(root.resolve(id).resolve("payload")))

        // After its backoff, the next run sends only the start, which the journal replays.
        val later = Journal(started())
        assertEquals(ImportStageResult.CLEAN, deliverImports(later, store, ImportRun(), "A", "2.0.52", ImportVersionGate.SUPPORTED, { true }, { Long.MAX_VALUE / 2 }, { _, _ -> }))
        assertEquals(listOf(start), later.paths)
        assertNull(dao.byId(id))
    }

    @Test
    fun everyRetryTellsTheOwnersEventLogWhichStepAndWhy() {
        setUp()
        val id = share("private name.pdf")
        val lines = mutableListOf<String>()
        fun runAfterBackoff(journal: PlHttpClient) =
            deliverImports(journal, store, ImportRun(), "A", "2.0.52", ImportVersionGate.SUPPORTED, { true }, { Long.MAX_VALUE / 2 }, { _, _ -> }, { lines += it })

        assertEquals(ImportStageResult.RETRY, deliver(Journal(json(503, "{}")), diag = { lines += it }))
        assertEquals(ImportStageResult.RETRY, runAfterBackoff(Journal(saved(), SocketTimeoutException("read timed out"))))
        assertEquals(ImportStageResult.CLEAN, runAfterBackoff(Journal(started())))
        assertEquals(
            listOf(
                "kind=import id=$id step=save outcome=retry cause=http-503",
                "kind=import id=$id step=start outcome=retry cause=io-SocketTimeoutException",
            ),
            lines,
        )
        assertTrue(lines.none { "private" in it })
    }

    @Test
    fun aRetryOnDirectIsTriedAgainOnRelayInTheSameRunWithoutCountingTwice() {
        setUp()
        val id = share()
        val run = ImportRun(cap = 1)
        assertEquals(ImportStageResult.RETRY, deliver(Journal(IOException("direct dropped")), run))
        assertEquals(setOf(id), run.retried)
        val relay = Journal(saved(), started())
        assertEquals(ImportStageResult.CLEAN, deliver(relay, run))
        assertEquals(listOf(save, start), relay.paths)
        assertEquals(1, run.selected.size)
        assertNull(dao.byId(id))
    }

    @Test
    fun aRefusedPairingStopsEverything() {
        setUp()
        share("a.pdf")
        share("b.pdf")
        val journal = Journal(json(403, """{"error":"forbidden","reason_code":"pl_revoked"}"""))
        val run = ImportRun()
        assertEquals(ImportStageResult.AUTH_STOP, deliver(journal, run))
        assertEquals(listOf(save), journal.paths)
        assertFalse(run.stageClean)
    }

    @Test
    fun aRefusedItemNeedsAttentionAndTheStageStaysClean() {
        setUp()
        val id = share()
        val run = ImportRun()
        assertEquals(ImportStageResult.CLEAN, deliver(Journal(refused413()), run))
        assertEquals("attention", dao.byId(id)!!.state)
        assertEquals("that file is too large", dao.byId(id)!!.attentionError)
        assertTrue(run.stageClean)
        // Not sent again to the same journal at the same version.
        val again = Journal()
        assertEquals(ImportStageResult.CLEAN, deliver(again))
        assertTrue(again.paths.isEmpty())
    }

    @Test
    fun aSaveAnswerAfterRePairingSendsNoStart() {
        setUp()
        val id = share()
        var current = true
        val journal = object : PlHttpClient {
            val paths = mutableListOf<String>()
            override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Int): HttpResponse {
                paths += path
                current = false // the owner re-pairs while this request is out
                return saved()
            }
        }
        assertEquals(ImportStageResult.PAIRING_LEFT, deliver(journal, pairingCurrent = { current }))
        assertEquals(listOf(save), journal.paths)
        assertEquals("pending", dao.byId(id)!!.state)
        assertFalse(store.isInFlight(id))
    }

    @Test
    fun aDeliveredAnswerFromTheJournalTheRequestWentToCountsAfterRePairing() {
        setUp()
        val id = share()
        var current = true
        val journal = object : PlHttpClient {
            override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Int): HttpResponse {
                current = false
                return alreadyImported()
            }
        }
        deliver(journal, pairingCurrent = { current })
        assertNull(dao.byId(id), "the journal the request went to holds it")
    }

    @Test
    fun nothingGoesToAJournalTheGateHolds() {
        setUp()
        val id = share()
        listOf(ImportVersionGate.UNKNOWN, ImportVersionGate.TOO_OLD).forEach { gate ->
            val journal = Journal()
            val run = ImportRun()
            assertEquals(ImportStageResult.HELD, deliver(journal, run, gate = gate))
            assertTrue(journal.paths.isEmpty())
            assertFalse(run.stageClean, "a held stage never asks for a follow-up")
        }
        assertEquals(0, dao.byId(id)!!.attemptCount)
    }

    @Test
    fun oneRunTakesAtMostItsCapAndSaysSo() {
        setUp()
        repeat(20) { share("doc$it.pdf") }
        val script = (0 until 16).flatMap { listOf(saved("20261019_1015${"%02d".format(it)}"), started()) }.toTypedArray()
        val run = ImportRun(cap = 16)
        assertEquals(ImportStageResult.CLEAN, deliver(Journal(*script), run))
        assertEquals(16, run.selected.size)
        assertTrue(run.stoppedByCap)
        assertTrue(run.stageClean)
        assertEquals(4, dao.rows.size)
    }

    @Test
    fun anItemRemovedBetweenSaveAndStartIsLeftGone() {
        setUp()
        val id = share()
        val journal = object : PlHttpClient {
            val paths = mutableListOf<String>()
            override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Int): HttpResponse {
                paths += path
                dao.rows.remove(id) // gone from under the stage (a test stand-in for a vanished row)
                return saved()
            }
        }
        assertEquals(ImportStageResult.CLEAN, deliver(journal))
        assertEquals(listOf(save), journal.paths, "no start for an item that is gone")
        assertNull(dao.byId(id))
    }
}
