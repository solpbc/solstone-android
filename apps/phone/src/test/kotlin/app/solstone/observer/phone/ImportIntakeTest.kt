// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.observer.IntakeRefusal
import app.solstone.platform.persistence.room.ImportCustodyStore
import app.solstone.platform.persistence.room.ImportDao
import app.solstone.platform.persistence.room.ImportDeliveryRow
import app.solstone.platform.persistence.room.ImportItemRow
import app.solstone.platform.persistence.room.ImportRecentReceiptRow
import app.solstone.platform.persistence.room.SourceIntakeRow
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportIntakeTest {
    private class Dao : ImportDao {
        val rows = ConcurrentHashMap<String, ImportItemRow>()
        override fun insert(row: ImportItemRow) { rows[row.id] = row }
        override fun update(row: ImportItemRow): Int = if (rows.containsKey(row.id)) { rows[row.id] = row; 1 } else 0
        override fun delete(id: String): Int = if (rows.remove(id) != null) 1 else 0
        override fun byId(id: String): ImportItemRow? = rows[id]
        override fun all(): List<ImportItemRow> = rows.values.sortedBy { it.createdAt }
        override fun putDelivery(row: ImportDeliveryRow) = Unit
        override fun delivery(): ImportDeliveryRow? = null
        override fun putRecentReceipt(row: ImportRecentReceiptRow) = Unit
        override fun recentReceipts(instanceId: String, cutoff: Long, limit: Int): List<ImportRecentReceiptRow> = emptyList()
        override fun pruneExpiredReceipts(cutoff: Long): Int = 0
        override fun trimRecentReceipts(instanceId: String, keep: Int): Int = 0
        override fun intakeBySource(sourceId: String): SourceIntakeRow? = null
        override fun upsertIntake(row: SourceIntakeRow) = Unit
    }

    /** Runs work on the calling thread, so a test sees the outcome when submit returns. */
    private object Now : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private val root: File = Files.createTempDirectory("intake").toFile()
    private val dao = Dao()
    private var runs = 0

    private fun intake(
        space: Long = 10L * 1024 * 1024 * 1024,
        maxBytes: Long = 4L * 1024 * 1024 * 1024,
        minFree: Long = 256L * 1024 * 1024,
        canConvertHeic: Boolean = true,
        convert: (File, File) -> Unit = { _, target -> target.writeBytes(byteArrayOf(1, 2, 3)) },
    ) = ImportIntake(
        store = ImportCustodyStore(root.toPath(), dao),
        root = root,
        requestRun = { runs++ },
        usableSpace = { space },
        canConvertHeic = canConvertHeic,
        maxBytes = maxBytes,
        minFree = minFree,
        convertHeic = convert,
        executor = Now,
    )

    private fun stream(name: String?, mime: String?, bytes: ByteArray, scheme: String? = "content", size: Long? = bytes.size.toLong()) =
        IntakeItem.Stream(name, mime, scheme, size, ByteArrayInputStream(bytes))

    private fun take(intake: ImportIntake, vararg items: IntakeItem): IntakeOutcome {
        var outcome: IntakeOutcome? = null
        intake.submit(items.toList()) { outcome = it }
        return outcome!!
    }

    private fun payloadOf(name: String): ByteArray {
        val row = dao.rows.values.single { it.displayName == name }
        return File(File(root, row.id), "payload").readBytes()
    }

    @Test
    fun aSharedFileIsSavedOnTheDeviceWithItsBytesAndASyncIsAskedFor() {
        val outcome = take(intake(), stream("notes.pdf", "application/pdf", "pdf-bytes".toByteArray()))
        assertEquals(1, outcome.accepted)
        assertEquals(emptyList(), outcome.refused)
        val row = dao.rows.values.single()
        assertEquals(listOf(IntakeSaved(row.id, "notes.pdf")), outcome.saved, "each saved item names its queue id")
        assertEquals("pdf-bytes", String(payloadOf("notes.pdf")))
        assertEquals(1, runs)
    }

    @Test
    fun oneBadItemIsRefusedAloneAndTheRestAreSaved() {
        val outcome = take(
            intake(),
            stream("a.pdf", "application/pdf", "a".toByteArray()),
            IntakeItem.Stream("b.pdf", "application/pdf", "content", 1, null),
            stream("c.mp4", "video/mp4", "c".toByteArray()),
            stream("d.png", "image/png", "d".toByteArray(), scheme = "file"),
            IntakeItem.Text("a note"),
        )
        assertEquals(3, outcome.accepted, "a video is the journal's to decide, so it is saved too")
        assertEquals(
            listOf(IntakeRefused("b.pdf", IntakeRefusal.UNREADABLE), IntakeRefused("d.png", IntakeRefusal.NOT_SHAREABLE)),
            outcome.refused,
        )
        assertEquals("text", dao.rows.values.single { it.payloadKind == "text" }.payloadKind)
    }

    @Test
    fun theSizeLimitIsExactAndAReportedSizeIsNotTrusted() {
        assertEquals(1, take(intake(maxBytes = 10), stream("ten.pdf", "application/pdf", ByteArray(10))).accepted)
        val over = take(intake(maxBytes = 10), stream("eleven.pdf", "application/pdf", ByteArray(11), size = 1))
        assertEquals(listOf(IntakeRefused("eleven.pdf", IntakeRefusal.TOO_LARGE)), over.refused)
        val claimed = take(intake(maxBytes = 10), stream("claims.pdf", "application/pdf", ByteArray(2), size = 50))
        assertEquals(listOf(IntakeRefused("claims.pdf", IntakeRefusal.TOO_LARGE)), claimed.refused, "a reported size over the limit refuses before copying")
        assertFalse(dao.rows.values.any { it.displayName in setOf("eleven.pdf", "claims.pdf") })
        assertTrue(File(root, ".staging").listFiles().orEmpty().isEmpty(), "refused copies leave no staging")
    }

    @Test
    fun aCopyStopsBeforeFreeSpaceFallsUnderCapturesFloor() {
        val outcome = take(intake(space = 300L * 1024 * 1024, minFree = 299L * 1024 * 1024), stream("big.pdf", "application/pdf", ByteArray(1024), size = null))
        assertEquals(listOf(IntakeRefused("big.pdf", IntakeRefusal.NO_SPACE)), outcome.refused)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun aFailingStreamMidCopyIsRefusedAndAbandoned() {
        val failing = object : InputStream() {
            var sent = 0
            override fun read(): Int = if (sent++ < 10) 1 else throw IOException("gone")
            override fun read(b: ByteArray, off: Int, len: Int): Int = if (sent++ < 1) { b[off] = 1; 1 } else throw IOException("gone")
        }
        val outcome = take(intake(), IntakeItem.Stream("cut.pdf", "application/pdf", "content", null, failing))
        assertEquals(listOf(IntakeRefused("cut.pdf", IntakeRefusal.UNREADABLE)), outcome.refused)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun heicIsConvertedToJpegWhereThePhoneCanAndRefusedWhereItCannot() {
        take(intake(), stream("IMG_1.HEIC", "image/heic", "heic".toByteArray()))
        val row = dao.rows.values.single()
        assertEquals("IMG_1.jpg", row.displayName)
        assertEquals("image/jpeg", row.mime)
        assertEquals(listOf<Byte>(1, 2, 3), payloadOf("IMG_1.jpg").toList())
        val old = take(intake(canConvertHeic = false), stream("IMG_2.HEIC", "image/heic", "heic".toByteArray()))
        assertEquals(listOf(IntakeRefused("IMG_2.HEIC", IntakeRefusal.CANNOT_CONVERT)), old.refused)
    }

    @Test
    fun aPhotoThatCantBeDecodedIsAConversionRefusalAndLeavesNothing() {
        val outcome = take(intake(convert = { _, _ -> throw IOException("not a photo") }), stream("IMG_3.HEIC", "image/heic", "junk".toByteArray()))
        assertEquals(listOf(IntakeRefused("IMG_3.HEIC", IntakeRefusal.CANNOT_CONVERT)), outcome.refused)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun aNameWithoutAnExtensionGetsOneFromItsType() {
        take(intake(), stream("1000012345", "image/jpeg", "j".toByteArray()), stream(null, "audio/mp4", "a".toByteArray()))
        assertEquals(setOf("1000012345.jpg", "file.m4a"), dao.rows.values.map { it.displayName }.toSet())
    }

    @Test
    fun anIntakeCutShortByTheProcessEndingIsReportedAndItsStagingReaped() {
        val first = intake()
        val staged = first.store.begin("file")!!
        File(root, ".intake-running").createNewFile()
        // A new process starts: the marker from the old one means that intake never finished.
        val next = intake()
        assertTrue(next.interrupted)
        assertFalse(File(File(root, ".staging"), staged).exists())
        next.dismissInterrupted()
        assertFalse(intake().interrupted)
    }

    @Test
    fun aFinishedIntakeLeavesNoMarker() {
        take(intake(), stream("x.pdf", "application/pdf", "x".toByteArray()))
        assertFalse(File(root, ".intake-running").exists())
        assertFalse(intake().interrupted)
    }

    @Test
    fun photoCaptionSurvivesCustodyAndFinishRemovesIt() {
        val item = IntakeItem.Stream(
            displayName = "IMG_4821.jpg",
            mime = "image/jpeg",
            scheme = "content",
            reportedSize = 4L,
            input = byteArrayOf(1, 2, 3, 4).inputStream(),
            caption = "  sunset from the porch  ",
        )
        val i = intake()
        val outcome = take(i, item)
        assertEquals(1, outcome.accepted)
        val storedId = i.store.items(null, null).single().id
        val descriptor = i.store.item(storedId)!!
        assertEquals("sunset from the porch", descriptor.caption)
        assertTrue(File(File(root, storedId), "caption.txt").isFile)
        i.store.remove(storedId)
        assertFalse(File(root, storedId).exists())
    }

    @Test
    fun aHandedOverPickKeepsItsCardAndContextBesideItsBytes() {
        val store = ImportCustodyStore(root.toPath(), dao)
        val context = """{"setting":"host conformance","future":{"preserve":true}}"""
        val outcome = take(
            intake(),
            IntakeItem.Stream("conversations.zip", null, "content", 3, ByteArrayInputStream("zip".toByteArray()), handoff = IntakeHandoff("chatgpt", context)),
        )
        val item = store.item(outcome.saved.single().id)!!
        assertEquals("chatgpt", item.area)
        assertEquals(context, item.context)
        assertEquals("conversations.zip", item.displayName, "an untyped file keeps its own name")
        assertEquals("waiting", store.handoffStatus(item.id).state)
    }
}
