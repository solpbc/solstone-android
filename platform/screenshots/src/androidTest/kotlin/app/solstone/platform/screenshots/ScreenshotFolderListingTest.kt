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
import app.solstone.core.screenshots.KnownFile
import app.solstone.core.screenshots.KnownVerdict
import app.solstone.core.screenshots.ReceivedEnd
import app.solstone.core.screenshots.ScreenshotBook
import app.solstone.core.screenshots.StagedShot
import app.solstone.core.screenshots.decodeBook
import app.solstone.core.screenshots.encodeBook
import app.solstone.core.screenshots.encodeKnown
import app.solstone.platform.foldersync.SafFolder
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

        // A complete inventory can still confirm a real absence.
        provider.listing = Listing.EMPTY
        assertTrue(session.runCheckBlocking())
        assertFalse(session.ui.countFailed)
        assertEquals(
            prior.frozen,
            decodeBook(bookFile.readText()).marks.filter { it.end == ReceivedEnd.GONE }.map { it.documentId }.toSet(),
        )
        assertEquals("", knownFile.readText())
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
