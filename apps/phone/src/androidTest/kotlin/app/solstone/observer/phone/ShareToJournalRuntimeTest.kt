// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareToJournalRuntimeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val created = mutableListOf<Uri>()

    private fun download(name: String, mime: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        created += uri
        return uri
    }

    private val store get() = ImportIntake.get(context).store

    private fun stagingIsEmpty(): Boolean =
        File(File(context.filesDir, "import"), ".staging").listFiles().orEmpty().isEmpty()

    @After
    fun cleanUp() {
        store.items(null, null).forEach { store.remove(it.id) }
        created.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    private fun share(uri: Uri, mime: String, extraFlags: Int = 0): Intent =
        Intent(context, ShareToJournalActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or extraFlags)

    @Test
    fun nothingIsCopiedUntilTheOwnerAddsItThenTheExactBytesAreSaved() {
        val bytes = "a pdf the owner shared".toByteArray()
        val uri = download("shared-${System.nanoTime()}.pdf", "application/pdf", bytes)
        ActivityScenario.launch<ShareToJournalActivity>(share(uri, "application/pdf")).use {
            compose.onNodeWithTag("shareSheet-add").assertExists()
            assertTrue("nothing is kept before add", store.items(null, null).isEmpty())
            assertTrue("nothing is staged before add", stagingIsEmpty())

            compose.onNodeWithTag("shareSheet-add").performClick()
            compose.waitUntil(10_000) { store.items(null, null).isNotEmpty() }
            compose.onNodeWithTag("shareSheet-done").assertExists()
        }
        val item = store.items(null, null).single()
        val payload = store.item(item.id)!!.payloadPath.toFile().readBytes()
        assertEquals(String(bytes), String(payload))
    }

    @Test
    fun cancelKeepsNothing() {
        val uri = download("cancelled-${System.nanoTime()}.pdf", "application/pdf", "x".toByteArray())
        ActivityScenario.launch<ShareToJournalActivity>(share(uri, "application/pdf")).use { scenario ->
            compose.onNodeWithTag("shareSheet-cancel").performClick()
            compose.waitUntil(5_000) { scenario.state == Lifecycle.State.DESTROYED }
        }
        assertTrue(store.items(null, null).isEmpty())
        assertTrue(stagingIsEmpty())
    }

    @Test
    fun aShareRelaunchedFromHistoryDoesNothing() {
        val uri = download("history-${System.nanoTime()}.pdf", "application/pdf", "x".toByteArray())
        ActivityScenario.launch<ShareToJournalActivity>(share(uri, "application/pdf", Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)).use { scenario ->
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
        assertTrue(store.items(null, null).isEmpty())
    }

    @Test
    fun anItemThatCantBeReadAtAddIsRefusedAloneAndTheOthersAreSaved() {
        val good = download("good-${System.nanoTime()}.pdf", "application/pdf", "good".toByteArray())
        val gone = download("gone-${System.nanoTime()}.pdf", "application/pdf", "gone".toByteArray())
        val other = download("also-${System.nanoTime()}.pdf", "application/pdf", "also".toByteArray())
        val intent = Intent(context, ShareToJournalActivity::class.java)
            .setAction(Intent.ACTION_SEND_MULTIPLE)
            .setType("application/pdf")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(good, gone, other))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ActivityScenario.launch<ShareToJournalActivity>(intent).use {
            compose.onNodeWithTag("shareSheet-add").assertExists()
            // The source goes away while the owner is still looking at the sheet.
            context.contentResolver.delete(gone, null, null)
            compose.onNodeWithTag("shareSheet-add").performClick()
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("shareSheet-done").assertExists() }.isSuccess }
        }
        assertEquals(2, store.items(null, null).size)
        val finished = ImportIntake.get(context).state.value as IntakeState.Finished
        assertEquals(2, finished.outcome.accepted)
        assertEquals(1, finished.outcome.refused.size)
    }

    @Test
    fun aShareOfOnlyWhatTheJournalCantTakeSaysSoAndOffersOnlyClose() {
        val uri = download("clip-${System.nanoTime()}.mp4", "video/mp4", "not really a video".toByteArray())
        ActivityScenario.launch<ShareToJournalActivity>(share(uri, "video/mp4")).use { scenario ->
            compose.onNodeWithTag("shareSheet-itemRefused").assertExists()
            compose.onNodeWithTag("shareSheet-add").assertDoesNotExist()
            compose.onNodeWithTag("shareSheet-close").performClick()
            compose.waitUntil(5_000) { scenario.state == Lifecycle.State.DESTROYED }
        }
        assertTrue(store.items(null, null).isEmpty())
        assertTrue(stagingIsEmpty())
    }
}
