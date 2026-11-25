// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screenshots

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.screenshots.KnownFile
import app.solstone.core.screenshots.KnownVerdict
import app.solstone.core.screenshots.ReceivedEnd
import app.solstone.core.screenshots.ScreenshotBook
import app.solstone.core.screenshots.StagedShot
import app.solstone.core.screenshots.decodeBook
import app.solstone.core.screenshots.encodeBook
import app.solstone.core.screenshots.encodeKnown
import app.solstone.core.screenshots.screenshotKeys
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SCREENSHOTS_STREAM
import app.solstone.platform.foldersync.DISCARDED_LOCALLY
import app.solstone.platform.foldersync.FolderDelivery
import app.solstone.platform.foldersync.SafFolder
import app.solstone.platform.foldersync.journalHasSegment
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ScreenshotFolderListingTest {
    private lateinit var files: File
    private lateinit var context: Context
    private lateinit var database: SolstonePersistenceDatabase
    private lateinit var provider: FolderProvider
    private lateinit var session: ScreenshotSession
    private val tree = Uri.parse("content://app.solstone.test.folder/tree/root")
    private val prior = ScreenshotBook(
        treeUri = tree.toString(),
        confirmed = true,
        frozen = setOf("unsent", "not-staged"),
        staged = listOf(StagedShot("unsent", "hash", "2026-01-01T00:00:00Z", "file", "waiting", 1)),
        lastCheckedMs = 123,
    )
    private val cached = encodeKnown(listOf(KnownFile("other-photo", 10, 123, KnownVerdict.NotScreenshot)))
    private val bookFile get() = files.resolve("screenshots/book.txt")
    private val knownFile get() = files.resolve("screenshots/known.txt")
    private var enabled = true

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        files = Files.createTempDirectory(app.cacheDir.toPath(), "screenshots-listing-").toFile()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
        }
        database = Room.inMemoryDatabaseBuilder(app, SolstonePersistenceDatabase::class.java).build()
        provider = FolderProvider().apply {
            attachInfo(app, ProviderInfo().apply {
                authority = tree.authority
                exported = false
                applicationInfo = app.applicationInfo
            })
        }
    }

    @After fun tearDown() {
        if (::session.isInitialized) session.close()
        database.close()
        files.deleteRecursively()
    }

    private fun startWith(book: ScreenshotBook) {
        bookFile.parentFile!!.mkdirs()
        bookFile.writeText(encodeBook(book))
        knownFile.writeText(cached)
        book.lastCheckedMs?.let { files.resolve("screenshots/checked.txt").writeText(it.toString()) }
        session = ScreenshotSession(
            context, database, files.resolve("spool").toPath(),
            wishOn = { enabled }, setEnabled = { enabled = it; true },
            ownerStopped = { false }, onSealed = { error("unexpected delivery") }, onChanged = {},
            folder = SafFolder(ContentResolver.wrap(provider)) { it == tree },
        )
    }

    @Test fun incompleteListingsKeepTheBackfillAndKnownFiles() {
        startWith(prior)
        for (result in Listing.entries.filter { it != Listing.EMPTY }) {
            provider.listing = result
            assertFalse(result.name, session.runCheckBlocking())
            assertTrue(result.name, session.ui.countFailed)
            assertEquals(result.name, 0, session.ui.backfillDone)
            assertEquals(result.name, 1, session.ui.waiting)
            assertEquals(result.name, encodeBook(prior), bookFile.readText())
            assertEquals(result.name, cached, knownFile.readText())
            assertEquals(result.name, 123L, session.ui.lastChecked)
        }

        // A complete inventory can still confirm a real absence and drops its waiting item.
        provider.listing = Listing.EMPTY
        assertTrue(session.runCheckBlocking())
        assertFalse(session.ui.countFailed)
        val after = decodeBook(bookFile.readText())
        assertEquals(
            prior.frozen,
            after.marks.filter { it.end == ReceivedEnd.GONE }.map { it.documentId }.toSet(),
        )
        assertTrue(after.staged.isEmpty())
        assertEquals(0, session.ui.waiting)
        assertEquals("", knownFile.readText())
    }

    @Test fun aCompleteListingDropsStagedQueueAndSpoolBytesWhileAnIncompleteListingKeepsThem() {
        val spool = files.resolve("spool").toPath()
        val delivery = FolderDelivery(spool, database)
        val frozenId = sealFixture(delivery, "2026-11-03T14:32:07Z", byteArrayOf(1, 2, 3, 4))
        val liveId = sealFixture(delivery, "2026-11-03T14:32:09Z", byteArrayOf(5, 6, 7, 8))
        val frozenRow = requireNotNull(database.segmentDao().segmentById(frozenId))
        val liveRow = requireNotNull(database.segmentDao().segmentById(liveId))
        val frozenDir = spool.resolve(frozenRow.day).resolve(frozenRow.stream).resolve(frozenRow.dirSegment)
        val liveDir = spool.resolve(liveRow.day).resolve(liveRow.stream).resolve(liveRow.dirSegment)
        assertTrue(Files.isRegularFile(frozenDir.resolve("screenshot_screen.png")))
        assertTrue(Files.isRegularFile(liveDir.resolve("screenshot_screen.png")))

        val book = ScreenshotBook(
            treeUri = tree.toString(),
            confirmed = true,
            frozen = setOf("frozen-shot"),
            staged = listOf(
                StagedShot("frozen-shot", "hash-frozen", "2026-11-03T14:32:07Z", "platform", frozenId, 1, tree.toString()),
                StagedShot("live-shot", "hash-live", "2026-11-03T14:32:09Z", "platform", liveId, 2, tree.toString()),
            ),
            lastCheckedMs = 123,
        )
        startWith(book)

        for (result in Listing.entries.filter { it != Listing.EMPTY }) {
            provider.listing = result
            assertFalse(result.name, session.runCheckBlocking())
            assertEquals(result.name, 2, session.ui.waiting)
            assertEquals(result.name, 2, database.segmentDao().segmentsForDrain(SCREENSHOTS_STREAM).size)
            assertEquals(result.name, 2, database.segmentDao().pendingCount(SCREENSHOTS_STREAM))
            assertTrue(result.name, Files.isRegularFile(frozenDir.resolve("screenshot_screen.png")))
            assertTrue(result.name, Files.isRegularFile(liveDir.resolve("screenshot_screen.png")))
        }

        provider.listing = Listing.EMPTY
        assertTrue(session.runCheckBlocking())
        val after = decodeBook(bookFile.readText())
        assertTrue(after.staged.isEmpty())
        assertTrue(after.received.isEmpty())
        assertEquals(0, session.ui.waiting)
        assertEquals(1, session.ui.backfillDone)
        assertEquals(1, session.ui.backfillTotal)
        assertEquals(listOf(ReceivedEnd.GONE), after.marks.map { it.end })
        assertTrue(database.segmentDao().segmentsForDrain(SCREENSHOTS_STREAM).isEmpty())
        assertEquals(0, database.segmentDao().pendingCount(SCREENSHOTS_STREAM))
        for (id in listOf(frozenId, liveId)) {
            val row = requireNotNull(database.segmentDao().segmentById(id))
            assertEquals(QueueState.EVICTED, row.state)
            assertEquals(DISCARDED_LOCALLY, row.lastError)
            assertTrue(row.localCopyAbsent)
            assertFalse(row.journalHasSegment())
        }
        assertFalse(Files.exists(frozenDir))
        assertFalse(Files.exists(liveDir))
    }

    @Test fun deliveredAndOtherFolderStagedScreenshotsAreNotDiscardedAsDeleted() {
        val spool = files.resolve("spool").toPath()
        val delivery = FolderDelivery(spool, database)
        val uploadedId = sealFixture(delivery, "2026-11-03T14:35:00Z", byteArrayOf(9, 10, 11))
        val otherFolderId = sealFixture(delivery, "2026-11-03T14:35:05Z", byteArrayOf(12, 13, 14))
        database.segmentDao().updateState(uploadedId, QueueState.UPLOADED)
        database.segmentDao().recordUploaded(uploadedId)

        val otherFolderShot = StagedShot(
            "other-doc", "hash-other", "2026-11-03T14:35:05Z", "platform",
            otherFolderId, 2, "content://app.solstone.test.folder/tree/previous",
        )
        val book = ScreenshotBook(
            treeUri = tree.toString(),
            confirmed = true,
            frozen = setOf("uploaded-doc"),
            staged = listOf(
                StagedShot("uploaded-doc", "hash-uploaded", "2026-11-03T14:35:00Z", "platform", uploadedId, 1, tree.toString()),
                otherFolderShot,
            ),
        )
        startWith(book)

        provider.listing = Listing.EMPTY
        assertTrue(session.runCheckBlocking())
        val after = decodeBook(bookFile.readText())
        assertEquals(listOf(otherFolderShot), after.staged)
        assertEquals(1, session.ui.waiting)
        assertEquals(listOf("hash-uploaded"), after.received.filter { it.end == ReceivedEnd.DELIVERED }.map { it.sha256 })
        assertEquals(listOf(ReceivedEnd.DELIVERED), after.marks.map { it.end })
        assertTrue(database.segmentDao().segmentById(uploadedId).journalHasSegment())
        assertEquals(listOf(otherFolderId), database.segmentDao().segmentsForDrain(SCREENSHOTS_STREAM).map { it.id })
    }

    @Test fun prepareDeliveryDropsDeletedStagedScreenshotBeforeDrainWhileIncompleteListingKeepsIt() {
        val spool = files.resolve("spool").toPath()
        val delivery = FolderDelivery(spool, database)
        val shotId = sealFixture(delivery, "2026-11-03T14:40:00Z", byteArrayOf(21, 22, 23))
        val row = requireNotNull(database.segmentDao().segmentById(shotId))
        val shotDir = spool.resolve(row.day).resolve(row.stream).resolve(row.dirSegment)
        assertTrue(Files.isRegularFile(shotDir.resolve("screenshot_screen.png")))

        val book = ScreenshotBook(
            treeUri = tree.toString(),
            confirmed = true,
            staged = listOf(
                StagedShot("waiting-shot", "hash-waiting", "2026-11-03T14:40:00Z", "platform", shotId, 1, tree.toString()),
            ),
        )
        startWith(book)
        val client = object : PlHttpClient {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
                maxResponseBytes: Int,
            ): HttpResponse = HttpResponse(200, emptyMap(), """{"capabilities":["screenshots"]}""".toByteArray())
        }

        for (result in Listing.entries.filter { it != Listing.EMPTY }) {
            provider.listing = result
            assertTrue(result.name, session.prepareDelivery(client, pairingCurrent = { true }))
            assertEquals(result.name, 1, database.segmentDao().segmentsForDrain(SCREENSHOTS_STREAM).size)
            assertTrue(result.name, Files.isRegularFile(shotDir.resolve("screenshot_screen.png")))
        }

        provider.listing = Listing.EMPTY
        assertTrue(session.prepareDelivery(client, pairingCurrent = { true }))
        val after = decodeBook(bookFile.readText())
        assertTrue(after.staged.isEmpty())
        assertEquals(0, session.ui.waiting)
        assertTrue(database.segmentDao().segmentsForDrain(SCREENSHOTS_STREAM).isEmpty())
        assertEquals(0, database.segmentDao().pendingCount(SCREENSHOTS_STREAM))
        val afterRow = requireNotNull(database.segmentDao().segmentById(shotId))
        assertEquals(QueueState.EVICTED, afterRow.state)
        assertEquals(DISCARDED_LOCALLY, afterRow.lastError)
        assertTrue(afterRow.localCopyAbsent)
        assertFalse(Files.exists(shotDir))
    }

    private fun sealFixture(delivery: FolderDelivery, capturedAt: String, bytes: ByteArray): String {
        val keys = screenshotKeys(capturedAt)
        val segment = SealedSegment(
            stream = SCREENSHOTS_STREAM,
            key = SegmentKey(keys.day, keys.segment),
            wireKeys = keys,
            payloads = listOf(
                SegmentPayload(
                    sourceId = SCREENSHOTS_STREAM,
                    ref = PayloadRef("screenshot_screen.png", "image/png", bytes.size.toLong(), null),
                    captureStartEpochMs = keys.startEpochMs,
                    captureEndEpochMs = keys.endEpochMs,
                ),
            ),
            gaps = emptyList(),
            metadata = mapOf(
                "screenshotCapturedAt" to capturedAt,
                "screenshotTakenFrom" to "platform",
            ),
        )
        return requireNotNull(delivery.seal(segment, bytes))
    }

    @Test fun aListingThatFailsAtConfirmationDoesNotStartAnEmptyBackfill() {
        enabled = false
        val unconfirmed = prior.copy(confirmed = false, frozen = emptySet())
        startWith(unconfirmed)
        provider.listing = Listing.NULL
        session.confirm()
        // Join the actor without asking it to check a disabled source.
        assertTrue(session.runCheckBlocking())
        assertTrue(session.ui.countFailed)
        assertFalse(session.ui.confirmed)
        assertFalse(enabled)
        assertEquals(encodeBook(unconfirmed), bookFile.readText())
    }

    private enum class Listing { NULL, LOADING, ERROR, REPEATED_DIRECTORY, NESTED_FAILURE, EMPTY }

    private class FolderProvider : ContentProvider() {
        @Volatile var listing = Listing.EMPTY

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val directoryMime = DocumentsContract.Document.MIME_TYPE_DIR
            if (uri.lastPathSegment == "root") {
                return MatrixCursor(projection).apply { addRow(arrayOf<Any?>("root", "Screenshots", directoryMime, null, null, 0)) }
            }
            check(uri.lastPathSegment == "children")
            if (uri.pathSegments[uri.pathSegments.lastIndex - 1] == "nested") return null
            return when (listing) {
                Listing.NULL -> null
                Listing.LOADING -> MatrixCursor(projection).apply {
                    addRow(arrayOf<Any?>("partial", "file.png", "image/png", 123, 10, 0))
                    extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
                }
                Listing.ERROR -> MatrixCursor(projection).apply {
                    extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "unavailable") }
                }
                Listing.REPEATED_DIRECTORY -> MatrixCursor(projection).apply {
                    addRow(arrayOf<Any?>("root", "cycle", directoryMime, null, null, 0))
                }
                Listing.NESTED_FAILURE -> MatrixCursor(projection).apply {
                    addRow(arrayOf<Any?>("partial", "file.png", "image/png", 123, 10, 0))
                    addRow(arrayOf<Any?>("nested", "Nested", directoryMime, null, null, 0))
                }
                Listing.EMPTY -> MatrixCursor(projection)
            }
        }

        override fun getType(uri: Uri): String = DocumentsContract.Document.MIME_TYPE_DIR
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("folder write")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("folder write")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("folder write")
    }
}
