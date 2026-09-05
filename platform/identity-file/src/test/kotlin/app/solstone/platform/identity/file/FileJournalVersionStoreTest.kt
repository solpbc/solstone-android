// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.JournalVersionRecord
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileJournalVersionStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun loadReturnsNullWhenFileDoesNotExist() {
        val file = File(temp.root, "journal_version.tsv")
        val store = FileJournalVersionStore(file)
        assertNull(store.load())
    }

    @Test
    fun savesAndLoadsRecord() {
        val file = File(temp.root, "journal_version.tsv")
        val store = FileJournalVersionStore(file)
        val record = JournalVersionRecord(
            instanceId = "jid-12345",
            caChainFingerprint = "sha256:abcde",
            version = "2.5.1",
        )

        store.save(record)
        val loaded = store.load()

        assertEquals(record, loaded)
    }

    @Test
    fun overwritesExistingRecord() {
        val file = File(temp.root, "journal_version.tsv")
        val store = FileJournalVersionStore(file)
        val first = JournalVersionRecord("jid-1", "sha256:111", "1.0.0")
        val second = JournalVersionRecord("jid-2", "sha256:222", "2.0.0")

        store.save(first)
        store.save(second)

        assertEquals(second, store.load())
    }

    @Test
    fun clearDeletesFile() {
        val file = File(temp.root, "journal_version.tsv")
        val store = FileJournalVersionStore(file)
        store.save(JournalVersionRecord("jid-1", "sha256:111", "1.0.0"))

        store.clear()

        assertNull(store.load())
        assertEquals(false, file.exists())
    }

    @Test
    fun loadHandlesCorruptedFileGracefully() {
        val file = File(temp.root, "journal_version.tsv")
        file.writeText("corrupted\tdata\nwithout\tproper\tkeys\n")
        val store = FileJournalVersionStore(file)

        assertNull(store.load())
    }
}
