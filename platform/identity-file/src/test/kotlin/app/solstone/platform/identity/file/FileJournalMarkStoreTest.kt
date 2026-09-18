// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.JournalMark
import app.solstone.core.identity.JournalMarkIcon
import app.solstone.core.identity.JournalMarkRecord
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileJournalMarkStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun loadReturnsNullWhenFileDoesNotExist() {
        val file = File(temp.root, "journal_mark.json")
        val store = FileJournalMarkStore(file)
        assertNull(store.load())
    }

    @Test
    fun savesAndLoadsIdentifiedRecord() {
        val file = File(temp.root, "journal_mark.json")
        val store = FileJournalMarkStore(file)
        val record = JournalMarkRecord(
            instanceId = "jid-12345",
            mark = JournalMark(
                icon1 = JournalMarkIcon(
                    name = "piano",
                    svg = "<path d=\"M18.5 3H5.5\"/>",
                    colorName = "blue",
                    colorHex = "#3b82f6",
                    rot = 45,
                ),
                icon2 = JournalMarkIcon(
                    name = "key",
                    svg = "<circle cx=\"7.5\" cy=\"15.5\" r=\"5.5\"/>",
                    colorName = "purple",
                    colorHex = "#a855f7",
                    rot = 0,
                ),
                words = listOf("liquefy", "smock"),
            ),
        )

        store.save(record)
        val loaded = store.load()
        assertEquals(record, loaded)
    }

    @Test
    fun savesAndLoadsUncommittedNoneRecord() {
        val file = File(temp.root, "journal_mark.json")
        val store = FileJournalMarkStore(file)
        val record = JournalMarkRecord(
            instanceId = "jid-12345",
            mark = null,
        )

        store.save(record)
        val loaded = store.load()
        assertEquals(record, loaded)
    }

    @Test
    fun clearDeletesFile() {
        val file = File(temp.root, "journal_mark.json")
        val store = FileJournalMarkStore(file)
        store.save(JournalMarkRecord("jid-123", null))
        assertEquals(true, file.exists())

        store.clear()
        assertEquals(false, file.exists())
        assertNull(store.load())
    }
}
