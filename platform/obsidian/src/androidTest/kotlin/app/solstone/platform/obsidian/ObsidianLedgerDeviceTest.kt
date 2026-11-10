// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.platform.obsidian

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.obsidian.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class ObsidianLedgerDeviceTest {
    @Test fun maximumNoteAndFrozenBytesSurviveRestartAndFailedTransaction() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = "large-note-test"
        val text = "🌳".repeat(OBSIDIAN_MAX_TEXT_BYTES / 4)
        val bytes = (text + "tail").toByteArray(Charsets.UTF_8)
        val keys = obsidianWindow("2026-04-03T09:01:00-06:00")
        val batch = PreparedObsidianBatch(root, "test", "large.jsonl", keys, 1, 1)
        SqliteObsidianLedger(context).use { ledger ->
            ledger.transaction { ledger.put(root, "pending", "large", text); ledger.prepare(batch, bytes) }
            try { ledger.transaction { ledger.put(root, "pending", "large", "wrong"); error("rollback") } }
            catch (_: IllegalStateException) { }
        }
        SqliteObsidianLedger(context).use { ledger ->
            assertEquals(text, ledger.get(root, "pending", "large"))
            assertArrayEquals(bytes, ledger.batchBytes(root, batch.name))
            ledger.markSpooled(root, batch.name)
            assertArrayEquals(bytes, ledger.batchBytes(root, batch.name))
            ledger.retireBatch(root, batch.name)
            assertNull(ledger.batchBytes(root, batch.name))
            ledger.remove(root, "pending", "large")
        }
    }
}
