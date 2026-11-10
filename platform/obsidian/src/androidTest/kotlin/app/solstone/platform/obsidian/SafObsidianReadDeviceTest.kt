// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.platform.obsidian

import android.content.*
import android.content.pm.ProviderInfo
import android.database.*
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.solstone.core.obsidian.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class SafObsidianReadDeviceTest {
    private lateinit var file: File
    private lateinit var provider: ReadProvider
    private lateinit var reader: SafObsidianVault
    private val vault = ChosenObsidianVault("test", Uri.parse("content://app.solstone.test.read/tree/root"), "test")
    @Before fun before() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File.createTempFile("obsidian-read-", ".md", context.cacheDir).apply { writeText("old") }
        provider = ReadProvider(file)
        provider.attachInfo(context, ProviderInfo().apply { authority = "app.solstone.test.read"; exported = false; applicationInfo = context.applicationInfo })
        reader = SafObsidianVault(ContentResolver.wrap(provider))
    }
    @After fun after() { provider.opened.forEach { runCatching { it.close() } }; file.delete() }
    private fun entry() = ObsidianEntry("note.md", provider.modified, file.length(), "note")

    @Test fun mutationBetweenReadAndPostCheckDefersThenAStableReadSucceeds() {
        provider.changeOnSecondQuery = true
        assertEquals(ObsidianRead.Unavailable, reader.read(vault, entry(), CancellationSignal()))
        assertFalse(provider.opened.single().fileDescriptor.valid())
        val value = reader.read(vault, entry(), CancellationSignal()) as ObsidianRead.Stable
        assertEquals("newer", value.text)
        assertNotNull(value.fileId)
        assertTrue(provider.opened.all { !it.fileDescriptor.valid() })
        assertEquals(listOf("r", "r"), provider.modes)
    }
    @Test fun providerLoadingIsUnavailableAndDoesNotOpenTheNote() {
        provider.loading = true
        assertEquals(ObsidianRead.Unavailable, reader.read(vault, entry(), CancellationSignal()))
        assertTrue(provider.opened.isEmpty())
    }
    @Test fun anEmptyRegularFileIsStableAndTheDescriptorCloses() {
        file.writeText("")
        assertEquals("", (reader.read(vault, entry(), CancellationSignal()) as ObsidianRead.Stable).text)
        assertFalse(provider.opened.single().fileDescriptor.valid())
    }

    private class ReadProvider(private val file: File) : ContentProvider() {
        var modified = 1000L
        var changeOnSecondQuery = false
        var loading = false
        var queries = 0
        val opened = mutableListOf<ParcelFileDescriptor>()
        val modes = mutableListOf<String>()
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            queries++
            if (changeOnSecondQuery && queries == 2) { file.writeText("newer"); modified = 2000L; changeOnSecondQuery = false }
            return MatrixCursor(projection).apply {
                addRow(arrayOf("note", "note.md", "text/markdown", modified, file.length(), 0))
                if (loading) extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(mode == "r"); modes += mode
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { opened += it }
        }
        override fun getType(uri: Uri) = "text/markdown"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("vault write")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("vault write")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = error("vault write")
    }
}
