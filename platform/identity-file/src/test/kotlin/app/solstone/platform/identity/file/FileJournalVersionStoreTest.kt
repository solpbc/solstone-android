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
    fun savesAndLoadsRecordWithName() {
        val file = File(temp.root, "journal_version.tsv")
        val store = FileJournalVersionStore(file)
        val record = JournalVersionRecord(
            instanceId = "jid-12345",
            caChainFingerprint = "sha256:abcde",
            version = "2.5.1",
            name = "My Personal Journal",
        )

        store.save(record)
        val loaded = store.load()

        assertEquals(record, loaded)
    }

    @Test
    fun loadsLegacyThreeColumnFileWithNullName() {
        val file = File(temp.root, "journal_version.tsv")
        file.writeText("instanceId\tjid-12345\ncaChainFingerprint\tsha256:abcde\nversion\t2.5.1\n")
        val store = FileJournalVersionStore(file)
        val loaded = store.load()

        assertEquals(
            JournalVersionRecord(
                instanceId = "jid-12345",
                caChainFingerprint = "sha256:abcde",
                version = "2.5.1",
                name = null,
            ),
            loaded,
        )
    }

    @Test
    fun optionalAboutFieldsRoundTripAndMalformedOptionalValuesAreIgnored() {
        val file = File(temp.root, "journal_version.tsv")
        file.writeText(
            "instanceId\tjid-12345\n" +
                "caChainFingerprint\tsha256:abcde\n" +
                "version\t2.5.1\n" +
                "versionSeenAt\tbad\n" +
                "hostFactsAt\t-1\n" +
                "os\tubuntu\n" +
                "os_version\t24.04\n" +
                "arch\tx86_64\n" +
                "build\t42\n",
        )
        val store = FileJournalVersionStore(file)

        assertEquals(
            JournalVersionRecord(
                instanceId = "jid-12345",
                caChainFingerprint = "sha256:abcde",
                version = "2.5.1",
                os = "ubuntu",
                osVersion = "24.04",
                arch = "x86_64",
                build = "42",
            ),
            store.load(),
        )

        val record = JournalVersionRecord(
            instanceId = "jid-1",
            caChainFingerprint = "sha256:ca1",
            version = "v2.5.1",
            name = "Home",
            os = "ubuntu",
            osVersion = "24.04",
            arch = "x86_64",
            build = "42",
            versionSeenAt = 1_700_000_000_123L,
            hostFactsAt = 1_700_000_001_456L,
        )
        store.save(record)
        assertEquals(record, store.load())
    }

    @Test
    fun overwritesExistingRecord() {
        val file = File(temp.root, "journal_version.tsv")
        val store = FileJournalVersionStore(file)
        val first = JournalVersionRecord("jid-1", "sha256:111", "1.0.0", "Old Name")
        val second = JournalVersionRecord("jid-2", "sha256:222", "2.0.0", "New Name")

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
