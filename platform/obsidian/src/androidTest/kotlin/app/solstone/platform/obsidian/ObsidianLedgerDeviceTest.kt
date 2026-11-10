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
    @Test fun stagedReadsResumeAcrossRealDatabaseReopensWithoutAdvancingReportedState() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = "read-stage-${java.util.UUID.randomUUID()}"
        val time = 1_790_000_000_000L
        val entries = (1..5).map { ObsidianEntry("$it.md", null, null, "$it") }
        val seen = mutableListOf<String>()
        repeat(3) { pass ->
            SqliteObsidianLedger(context).use { ledger ->
                val stage = ObsidianReadStage(ledger, root)
                var reads = 0; var stop = false
                try {
                    stage.fill(entries, { seen += it.path; reads++; ObsidianRead.Stable(it.path) },
                        { time + pass * 1000 }, { stop }, { _, _ -> if (reads == 2) stop = true })
                } catch (_: java.util.concurrent.CancellationException) { }
                assertTrue(ledger.keys(root, "rows").isEmpty())
                assertTrue(ledger.keys(root, "pending").isEmpty())
            }
        }
        assertEquals(entries.map { it.path }, seen)
        SqliteObsidianLedger(context).use { ledger ->
            val stage = ObsidianReadStage(ledger, root)
            val engine = ObsidianEngine(ledger, java.time.ZoneId.of("UTC"))
            engine.scan(root, "test", entries, time + 600_000, stage::read, readStageToken = stage.token)
            stage.clearApplied()
            engine.closeDueWindows(time + 1_200_000)
            val lines = ledger.batches().filter { it.root == root }.flatMap {
                ledger.batchBytes(root, it.name)!!.toString(Charsets.UTF_8).lineSequence().filter(String::isNotEmpty).map(::decodeObsidianChange).toList()
            }
            assertEquals(5, lines.size)
            assertEquals(obsidianStamp(time, java.time.ZoneId.of("UTC")), lines.first().observedAt)
            assertTrue(ledger.keys(root, "read_stage").isEmpty())
        }
    }

}
