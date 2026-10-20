// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.observer.IntakeRefusal
import app.solstone.platform.persistence.room.ImportCustodyStore
import app.solstone.platform.persistence.room.ImportDao
import app.solstone.platform.persistence.room.ImportDeliveryRow
import app.solstone.platform.persistence.room.ImportItemRow
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
        assertEquals(IntakeOutcome(1, emptyList()), outcome)
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
        assertEquals(2, outcome.accepted)
        assertEquals(
            listOf(IntakeRefused("b.pdf", IntakeRefusal.UNREADABLE), IntakeRefused("c.mp4", IntakeRefusal.UNSUPPORTED_TYPE), IntakeRefused("d.png", IntakeRefusal.NOT_SHAREABLE)),
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
}
