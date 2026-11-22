// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.platform.persistence.room.IMPORT_STATE_ATTENTION
import app.solstone.platform.persistence.room.IMPORT_STATE_PENDING
import app.solstone.platform.persistence.room.IMPORT_STATE_READY_TO_REVIEW
import app.solstone.platform.persistence.room.IMPORT_STATE_REVIEW_EXPIRED
import app.solstone.platform.persistence.room.ImportChange
import app.solstone.platform.persistence.room.ImportCustodyStore
import app.solstone.platform.persistence.room.ImportDao
import app.solstone.platform.persistence.room.ImportDeliveryRow
import app.solstone.platform.persistence.room.ImportItemRow
import app.solstone.platform.persistence.room.ImportRecentReceiptRow
import app.solstone.platform.persistence.room.SourceIntakeRow
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
            recent.values
                .filter { it.instanceId == instanceId && it.deliveredAt >= cutoff }
                .sortedWith(compareByDescending<ImportRecentReceiptRow> { it.deliveredAt }.thenByDescending { it.id })
                .take(limit)
        override fun pruneExpiredReceipts(cutoff: Long): Int {
            val expired = recent.values.filter { it.deliveredAt < cutoff }.map { it.id }
            expired.forEach { recent.remove(it) }
            return expired.size
        }
        override fun trimRecentReceipts(instanceId: String, keep: Int): Int {
            val keepIds = recent.values
                .filter { it.instanceId == instanceId }
                .sortedWith(compareByDescending<ImportRecentReceiptRow> { it.deliveredAt }.thenByDescending { it.id })
                .take(keep)
                .map { it.id }
                .toSet()
            val drop = recent.values.filter { it.instanceId == instanceId && it.id !in keepIds }.map { it.id }
            drop.forEach { recent.remove(it) }
            return drop.size
        }
        override fun intakeBySource(sourceId: String): SourceIntakeRow? = null
        override fun upsertIntake(row: SourceIntakeRow) = Unit
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
    private var testClock: Long = 1_000L

    private fun setUp(): ImportCustodyStore {
        root = Files.createTempDirectory("import-delivery")
        dao = Dao()
        testClock = 1_000L
        store = ImportCustodyStore(root = root, dao = dao, clock = { testClock })
        return store
    }

    private fun share(name: String = "doc.pdf", mime: String = "application/pdf", caption: String? = null): String {
        testClock += 1L
        val id = store.begin("file")!!
        val bytes = "pdf-bytes-$name".toByteArray()
        val stageDir = root.resolve(".staging").resolve(id)
        Files.write(stageDir.resolve("payload"), bytes)
        if (caption != null) {
            Files.write(stageDir.resolve("caption.txt"), caption.toByteArray(Charsets.UTF_8))
        }
        assertTrue(store.publish(id, name, mime, bytes.size.toLong()))
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

    @Test
    fun aCaptionedPhotoIsHeldOnJournalsOlderThan2086AndDeliversOnceUpdated() {
        setUp()
        val id = share("photo.jpg", mime = "image/jpeg", caption = "sunset from the porch")
        val oldJournal = Journal()
        val run = ImportRun()
        assertEquals(ImportStageResult.HELD, deliver(oldJournal, run, version = "2.0.85"))
        assertTrue(oldJournal.paths.isEmpty())
        assertFalse(run.stageClean, "a held captioned photo must not trigger a follow-up loop")
        assertFalse(hasDeliverableImports(store, "A", "2.0.85", 2_000L))
        assertEquals(0, dao.byId(id)!!.attemptCount)
        assertEquals("sunset from the porch", store.item(id)!!.caption)

        val updatedJournal = Journal(saved(), started())
        assertTrue(hasDeliverableImports(store, "A", "2.0.86", 2_000L))
        assertEquals(ImportStageResult.CLEAN, deliver(updatedJournal, ImportRun(), version = "2.0.86"))
        assertEquals(listOf(save, start), updatedJournal.paths)
        assertNull(dao.byId(id))
    }

    @Test
    fun anUncaptionedItemStillDeliversOn2085WhileACaptionedPhotoStaysHeld() {
        setUp()
        val captioned = share("photo.jpg", mime = "image/jpeg", caption = "keep me")
        val plain = share("notes.pdf")
        val journal = Journal(saved(), started())
        val run = ImportRun(cap = 1)
        assertEquals(ImportStageResult.CLEAN, deliver(journal, run, version = "2.0.85"))
        assertEquals(listOf(save, start), journal.paths)
        assertEquals(setOf(plain), run.selected)
        assertNull(dao.byId(plain))
        assertNotNull(dao.byId(captioned))
        assertEquals("keep me", store.item(captioned)!!.caption)
        assertFalse(hasDeliverableImports(store, "A", "2.0.85", 2_000L))
    }

    @Test
    fun aCaptionedPhotoAlreadyStagedOn2085StillFinishesStart() {
        setUp()
        val id = share("photo.jpg", mime = "image/jpeg", caption = "already staged")
        assertTrue(store.markSaved(id, "/imports/20261120_100000/photo.jpg", "20261120_100000", "A"))
        val journal = Journal(started())
        assertEquals(ImportStageResult.CLEAN, deliver(journal, ImportRun(), version = "2.0.85"))
        assertEquals(listOf(start), journal.paths)
        assertNull(dao.byId(id))
    }

    @Test
    fun confirmationRequiredPreviewEntersReadyToReviewAndPlanFailedEntersAttention() {
        setUp()
        val appleId = share("export.zip", mime = "application/zip")
        val brokenId = share("broken.zip", mime = "application/zip")
        val journal = Journal(
            saved("20261122_130001"),
            json(
                409,
                """{"error":"confirm","reason_code":"import_preview_required","kind":"body_confirmation_required","import_id":"20261122_130001"}""",
            ),
            saved("20261122_130002"),
            json(
                409,
                """{"error":"bad archive","reason_code":"import_preview_required","kind":"plan_failed","import_id":"20261122_130002"}""",
            ),
        )
        val run = ImportRun()
        assertEquals(ImportStageResult.CLEAN, deliver(journal, run, version = "2.0.99"))
        assertTrue(run.stageClean)

        val appleRow = dao.byId(appleId)!!
        assertEquals(IMPORT_STATE_READY_TO_REVIEW, appleRow.state)
        assertEquals("20261122_130001", appleRow.reviewImportId)
        assertEquals("body_confirmation_required", appleRow.reviewKind)
        assertEquals("A", appleRow.reviewInstanceId)
        assertTrue(Files.exists(root.resolve(appleId).resolve("payload")), "local bytes stay until confirmed")

        val brokenRow = dao.byId(brokenId)!!
        assertEquals(IMPORT_STATE_ATTENTION, brokenRow.state)
        assertEquals("plan_failed", brokenRow.attentionKind)
        assertEquals("import_preview_required", brokenRow.attentionReasonCode)

        // Neither item is auto-retried on reconnect or version bump.
        val quiet = Journal()
        assertEquals(ImportStageResult.CLEAN, deliver(quiet, ImportRun(), version = "2.0.100"))
        assertTrue(quiet.paths.isEmpty())
    }

    @Test
    fun reconcileHandlesConfirmedWaitingExpiredTransientAuthStopAndInstanceSwitch() {
        setUp()
        val idConfirmed = share("health-1.zip", mime = "application/zip")
        val idWaiting = share("health-2.zip", mime = "application/zip")
        val idExpired = share("health-3.zip", mime = "application/zip")
        val idTransient = share("health-4.zip", mime = "application/zip")

        assertEquals(ImportChange.DONE, store.recordReviewRequired(idConfirmed, "20261122_140001", "import_preview_required", "body_confirmation_required", null, "A"))
        assertEquals(ImportChange.DONE, store.recordReviewRequired(idWaiting, "20261122_140002", "import_preview_required", "body_confirmation_required", null, "A"))
        assertEquals(ImportChange.DONE, store.recordReviewRequired(idExpired, "20261122_140003", "import_preview_required", "body_confirmation_required", null, "A"))
        assertEquals(ImportChange.DONE, store.recordReviewRequired(idTransient, "20261122_140004", "import_preview_required", "body_confirmation_required", null, "A"))

        val inspectJournal = Journal(
            json(200, """{"status":"running","saved_start":{"state":"consumed","active":true}}"""),
            json(200, """{"status":"pending","saved_start":{"state":"unknown","active":false}}"""),
            json(404, """{"error":"not found"}"""),
            json(503, """{"error":"busy"}"""),
        )
        val outcome = reconcileReadyToReviewImports(inspectJournal, store, "A", { true })
        assertEquals(ImportReconcileOutcome.TRANSIENT, outcome)
        assertEquals(
            listOf(
                "/app/import/api/20261122_140001",
                "/app/import/api/20261122_140002",
                "/app/import/api/20261122_140003",
                "/app/import/api/20261122_140004",
            ),
            inspectJournal.paths,
        )

        // Confirmed: deleted locally + 24h recent receipt persisted.
        assertNull(dao.byId(idConfirmed))
        assertFalse(Files.exists(root.resolve(idConfirmed)))
        assertEquals(listOf("20261122_140001"), store.recentReceipts("A").map { it.importId })

        // StillWaiting (including "unknown"): untouched and local bytes kept!
        assertEquals(IMPORT_STATE_READY_TO_REVIEW, dao.byId(idWaiting)?.state)
        assertTrue(Files.exists(root.resolve(idWaiting).resolve("payload")))

        // Expired (404): marked review_expired, local bytes kept, sendAgain re-delivers.
        assertEquals(IMPORT_STATE_REVIEW_EXPIRED, dao.byId(idExpired)?.state)
        assertTrue(Files.exists(root.resolve(idExpired).resolve("payload")))

        // Transient (503): left unchanged so next pass retries.
        assertEquals(IMPORT_STATE_READY_TO_REVIEW, dao.byId(idTransient)?.state)
        assertTrue(Files.exists(root.resolve(idTransient).resolve("payload")))

        // 401/403 halts batch immediately without deleting bytes or probing later items.
        val authJournal = Journal(json(401, """{"error":"unauthorized"}"""))
        assertEquals(ImportReconcileOutcome.AUTH_STOP, reconcileReadyToReviewImports(authJournal, store, "A", { true }))
        assertEquals(listOf("/app/import/api/20261122_140002"), authJournal.paths)
        assertEquals(IMPORT_STATE_READY_TO_REVIEW, dao.byId(idWaiting)?.state)
        assertEquals(IMPORT_STATE_READY_TO_REVIEW, dao.byId(idTransient)?.state)

        // Re-pair to Journal B resets Journal A's review & expired items back to pending without querying Journal B with Journal A's import_ids.
        val journalB = Journal()
        assertEquals(ImportReconcileOutcome.CLEAN, reconcileReadyToReviewImports(journalB, store, "B", { true }))
        assertTrue(journalB.paths.isEmpty())
        assertEquals(IMPORT_STATE_PENDING, dao.byId(idWaiting)?.state)
        assertEquals(IMPORT_STATE_PENDING, dao.byId(idExpired)?.state)
        assertEquals(IMPORT_STATE_PENDING, dao.byId(idTransient)?.state)
    }
}
