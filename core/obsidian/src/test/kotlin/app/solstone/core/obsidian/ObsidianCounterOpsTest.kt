// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.core.obsidian

import java.time.ZoneId
import kotlin.test.*

class ObsidianCounterOpsTest {
    private fun entry(path: String, time: Long) = ObsidianEntry(path, time, null, path)
    @Test fun baselineNewRenameAndQueueDrainKeepCausalIdentity() {
        val s = MemoryLedger(); val e = ObsidianEngine(s, ZoneId.of("UTC")); val t = 1_800_000_000_000L
        e.scan("one", "Notes", listOf(entry("old.md", t - 1000)), t, { ObsidianRead.Stable("old", "old") })
        val first = pendingObsidianCounters(s); assertEquals(listOf("baseline"), first.map { it.kind })
        retireObsidianCounters(s, first)
        e.scan("one", "Notes", listOf(entry("old.md", t - 1000), entry("new.md", t - 1000)), t + 1000,
            { ObsidianRead.Stable("new", "new", observedMs = t + 500) })
        val next = pendingObsidianCounters(s); assertEquals("new", next.single().kind)
        assertTrue(next.single().sequence > first.single().sequence)
        assertEquals(t + 500, next.single().observedMs)
        retireObsidianCounters(s, next)
        e.scan("one", "Notes", listOf(entry("old.md", t - 1000), entry("renamed.md", t + 2000)), t + 2000,
            { ObsidianRead.Stable(if (it.path == "old.md") "old" else "new", if (it.path == "old.md") "old" else "new") })
        val rename = pendingObsidianCounters(s).single()
        assertEquals("rename", rename.kind)
        assertEquals(obsidianHash("new.md".toByteArray()), rename.previousHash)
        assertEquals(obsidianHash("renamed.md".toByteArray()), rename.pathHash)
        assertTrue(rename.sequence > next.single().sequence)
        e.scan("two", "Other", listOf(entry("old.md", t)), t, { ObsidianRead.Stable("old") })
        assertEquals(setOf("one", "two"), pendingObsidianCounters(s).map { it.root }.toSet())
    }
    @Test fun failedEngineTransactionCannotLeaveACounterOperation() {
        val s = MemoryLedger(); val e = ObsidianEngine(s, ZoneId.of("UTC"))
        s.refuseNextBucket = "rows"
        assertFails { e.scan("one", "Notes", listOf(entry("a.md", 1000)), 2000, { ObsidianRead.Stable("text") }) }
        assertTrue(pendingObsidianCounters(s).isEmpty())
        assertNull(s.get("one", "meta", "counter_sequence"))
    }
}
