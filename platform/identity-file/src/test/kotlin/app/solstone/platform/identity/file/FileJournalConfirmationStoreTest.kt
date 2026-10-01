// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.JournalConfirmation
import app.solstone.core.identity.StoreInspectResult
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileJournalConfirmationStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun missingFileInspectsAsMissing() {
        val file = File(temp.root, "journal_confirmation.json")
        val store = FileJournalConfirmationStore(file)
        assertIs<StoreInspectResult.Missing>(store.inspect())
    }

    @Test
    fun confirmWritesVerbatimFingerprint() {
        val file = File(temp.root, "journal_confirmation.json")
        val store = FileJournalConfirmationStore(file)
        val latch = java.util.concurrent.CountDownLatch(2)
        val notifications = AtomicInteger(0)
        val removeListener = store.addListener {
            notifications.incrementAndGet()
            latch.countDown()
        }

        val fp = "sha256:abcd1234ef56"
        store.confirm(fp)

        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val inspected = assertIs<StoreInspectResult.Ready<JournalConfirmation>>(store.inspect())
        assertEquals(JournalConfirmation(confirmed = fp), inspected.value)
        assertEquals(2, notifications.get())
        removeListener()
    }

    @Test
    fun settleOnEmptyStoreWritesNullConfirmed() {
        val file = File(temp.root, "journal_confirmation.json")
        val store = FileJournalConfirmationStore(file)

        store.settle()

        val inspected = assertIs<StoreInspectResult.Ready<JournalConfirmation>>(store.inspect())
        assertEquals(JournalConfirmation(confirmed = null), inspected.value)
    }

    @Test
    fun settleOnExistingConfirmedPreservesConfirmedFingerprint() {
        val file = File(temp.root, "journal_confirmation.json")
        val store = FileJournalConfirmationStore(file)
        val fp = "sha256:existing-fp"
        store.confirm(fp)

        store.settle()

        val inspected = assertIs<StoreInspectResult.Ready<JournalConfirmation>>(store.inspect())
        assertEquals(JournalConfirmation(confirmed = fp), inspected.value)
    }

    @Test
    fun unreadableStorageReturnsUnreadable() {
        val file = File(temp.root, "journal_confirmation.json")
        file.writeText("invalid json string")
        val store = FileJournalConfirmationStore(file)
        assertIs<StoreInspectResult.Unreadable>(store.inspect())

        file.writeText("""{"unexpected":"format"}""")
        assertIs<StoreInspectResult.Unreadable>(store.inspect())
    }

    @Test
    fun settleThrowsOnIoFailure() {
        val dir = File(temp.root, "not-a-file")
        dir.mkdir()
        val file = File(dir, "sub/journal_confirmation.json")
        // Make parent directory read-only so write fails
        dir.setWritable(false)
        val store = FileJournalConfirmationStore(file)
        try {
            var threw = false
            try {
                store.settle()
            } catch (e: IOException) {
                threw = true
            }
            assertTrue(threw, "Expected IOException on I/O failure")
        } finally {
            dir.setWritable(true)
        }
    }
}
