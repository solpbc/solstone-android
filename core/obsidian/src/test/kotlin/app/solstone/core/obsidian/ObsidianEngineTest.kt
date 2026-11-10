// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.core.obsidian

import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.*

class ObsidianEngineTest {
    private val zone = ZoneId.of("America/Denver")
    private val t = OffsetDateTime.parse("2026-11-08T09:42:33-07:00").toInstant().toEpochMilli()
    private fun entry(path: String, at: Long = t - 1000, text: String = "first") = ObsidianEntry(path, at, text.toByteArray().size.toLong(), path)
    private fun lines(s: MemoryLedger): List<ObsidianChange> = s.batches().flatMap { batch ->
        requireNotNull(s.batchBytes(batch.root, batch.name)).toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotEmpty() }.map(::decodeObsidianChange).toList()
    }
    @Test fun goldenWireBytes() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/obsidian-notes-v1.jsonl")).readBytes()
        val encoded = bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }
            .joinToString("\n", postfix = "\n") { decodeObsidianChange(it).encode() }.toByteArray()
        assertContentEquals(bytes, encoded)
        assertEquals("3e100d06fb18b4f45302cb3cf2cad1881e7183360649f0543ce4acf565b9f81e", obsidianHash(encoded))
        val window = obsidianWindow(decodeObsidianChange(bytes.toString(Charsets.UTF_8).lineSequence().first()).changedAt)
        assertEquals("20261108", window.day); assertEquals("094000_300", window.segment)
    }
    @Test fun reportablePathsAndUnicodeOrdering() {
        assertTrue(reportableObsidianPath("daily/A.MD"))
        for (p in listOf("a.txt", "../a.md", "a//b.md", "/a.md", ".obsidian/a.md", "Templates/a.md", "x\\a.md", "a\u0000.md")) {
            assertFalse(reportableObsidianPath(p), p)
        }
        assertTrue(compareObsidianText("\ue000.md", "\ud83d\ude00.md") < 0)
    }
    @Test fun firstSyncCoalescesAndBaselinesCounter() {
        val s = MemoryLedger(); val e = ObsidianEngine(s, zone)
        e.scan("a", "Notes", listOf(entry("a.md")), t, { ObsidianRead.Stable("first") })
        e.scan("a", "Notes", listOf(entry("a.md", t + 1000, "edited")), t + 2000, { ObsidianRead.Stable("edited") })
        assertNull(s.get("a", "meta", "notes_total"))
        e.closeDueWindows(t + 300_000)
        assertEquals(listOf("edited"), lines(s).map { it.text })
        assertEquals(1, s.batches().single().initialNotes)
        e.scan("a", "Notes", listOf(entry("a.md", t + 1000, "edited"), entry("new.md", t + 400_000)), t + 400_000,
            { ObsidianRead.Stable("first") })
        assertEquals("1", s.get("a", "meta", "notes_total"))
    }
    @Test fun renameContinuesIdentityAndDeleteWaitsAnotherScan() {
        val s = MemoryLedger(); val e = ObsidianEngine(s, zone)
        e.scan("a", "Notes", listOf(entry("old.md")), t, { ObsidianRead.Stable("first", "inode") })
        e.closeDueWindows(t + 300_000)
        e.scan("a", "Notes", listOf(entry("new.md", t + 400_000, "second")), t + 400_000,
            { ObsidianRead.Stable("second", "inode") })
        e.closeDueWindows(t + 900_000)
        assertEquals("renamed", lines(s).last().change)
        assertEquals("old.md", lines(s).last().previousPath)
        assertNull(s.get("a", "meta", "notes_total"))
        e.scan("a", "Notes", emptyList(), t + 1_000_000, { error("no read") })
        e.closeDueWindows(t + 1_500_000)
        assertEquals(2, lines(s).size)
        e.scan("a", "Notes", emptyList(), t + 1_600_000, { error("no read") })
        e.closeDueWindows(t + 2_000_000)
        assertEquals("removed", lines(s).last().change)
    }
    @Test fun sameWindowFirstSightThenRemovalDropsWithoutStuckProgress() {
        val s = MemoryLedger(); val e = ObsidianEngine(s, zone)
        e.scan("a", "Notes", listOf(entry("a.md")), t, { ObsidianRead.Stable("first") })
        e.scan("a", "Notes", emptyList(), t + 1000, { error("no read") })
        e.scan("a", "Notes", emptyList(), t + 2000, { error("no read") })
        e.closeDueWindows(t + 300_000)
        assertTrue(s.batches().isEmpty()); assertEquals("0", s.get("a", "meta", "initial_total"))
    }
    @Test fun unreadableAndTooLargeStayPresentThenRecover() {
        val s = MemoryLedger(); val e = ObsidianEngine(s, zone)
        e.scan("a", "Notes", listOf(entry("a.md")), t, { ObsidianRead.Stable("first") })
        e.closeDueWindows(t + 300_000)
        val before = s.get("a", "rows", "a.md")
        val changed = entry("a.md", t + 400_000, "changed")
        assertEquals(1, e.scan("a", "Notes", listOf(changed), t + 400_000, { ObsidianRead.Unavailable }).unavailable)
        assertEquals(before, s.get("a", "rows", "a.md"))
        assertEquals(1, e.scan("a", "Notes", listOf(changed), t + 500_000, { ObsidianRead.TooLarge }).tooLarge)
        assertEquals(before, s.get("a", "rows", "a.md"))
        e.scan("a", "Notes", listOf(changed), t + 600_000, { ObsidianRead.Stable("changed") })
        e.closeDueWindows(t + 1_000_000)
        assertEquals(listOf("changed", "changed"), lines(s).map { it.change })
        assertEquals("changed", lines(s).last().text)
    }
    @Test fun oldMetadataChangedContentUsesObservedAndAlreadySealedWindowDefers() {
        val s = MemoryLedger(); val e = ObsidianEngine(s, zone)
        val first = entry("a.md").copy(modifiedMs = null, size = null)
        e.scan("a", "Notes", listOf(first), t, { ObsidianRead.Stable("first") })
        e.closeDueWindows(t + 300_000)
        val before = s.get("a", "rows", "a.md")
        e.scan("a", "Notes", listOf(first), t + 1000, { ObsidianRead.Stable("changed") })
        assertEquals(before, s.get("a", "rows", "a.md"))
        e.scan("a", "Notes", listOf(first), t + 400_000, { ObsidianRead.Stable("changed") })
        e.closeDueWindows(t + 1_000_000)
        assertEquals(2, lines(s).size)
        assertEquals(obsidianStamp(t + 400_000, zone), lines(s).last().changedAt)
    }
    @Test fun oversizedNewcomerCannotTurnARenameIntoADeletion() {
        val s = MemoryLedger(); val e = ObsidianEngine(s, zone)
        e.scan("a", "Notes", listOf(entry("old.md")), t, { ObsidianRead.Stable("first", "inode") })
        e.closeDueWindows(t + 300_000)
        val huge = ObsidianEntry("new.md", t + 400_000, OBSIDIAN_MAX_TEXT_BYTES.toLong() + 1, "new")
        e.scan("a", "Notes", listOf(huge), t + 400_000, { error("oversized must not be read") })
        e.scan("a", "Notes", listOf(huge), t + 500_000, { error("oversized must not be read") })
        e.closeDueWindows(t + 1_000_000)
        assertEquals(listOf("changed"), lines(s).map { it.change })
    }
    @Test fun presentButUnreadableResetsTheMissingHold() {
        val s = MemoryLedger(); val e = ObsidianEngine(s, zone)
        e.scan("a", "Notes", listOf(entry("a.md")), t, { ObsidianRead.Stable("first") })
        e.closeDueWindows(t + 300_000)
        e.scan("a", "Notes", emptyList(), t + 400_000, { error("empty") })
        e.scan("a", "Notes", listOf(ObsidianEntry("a.md", null, null, "a")), t + 500_000, { ObsidianRead.Unavailable })
        e.scan("a", "Notes", emptyList(), t + 600_000, { error("empty") })
        e.closeDueWindows(t + 1_000_000)
        assertEquals(listOf("changed"), lines(s).map { it.change })
        e.scan("a", "Notes", emptyList(), t + 1_100_000, { error("empty") })
        e.closeDueWindows(t + 1_500_000)
        assertEquals(listOf("changed", "removed"), lines(s).map { it.change })
    }
    @Test fun independentVaultInventoriesPreserveWaitingMaterial() {
        val s = MemoryLedger(); val e = ObsidianEngine(s, zone)
        e.scan("a", "A", listOf(entry("a.md")), t, { ObsidianRead.Stable("first") })
        e.scan("b", "B", listOf(entry("b.md")), t, { ObsidianRead.Stable("first") })
        e.closeDueWindows(t + 300_000)
        assertEquals(setOf("A", "B"), lines(s).map { it.vault }.toSet())
        e.scan("a", "A", listOf(entry("a.md")), t + 400_000, { error("unchanged note must not be read") })
        assertEquals(2, lines(s).size)
    }
    @Test fun cancelledScanRollsBackMetadataAndPendingTogether() {
        val s = MemoryLedger(); val e = ObsidianEngine(s, zone); var stop = false
        assertFails { e.scan("a", "Notes", listOf(entry("a.md"), entry("b.md")), t,
            { ObsidianRead.Stable("first") }, cancelled = { stop }, progress = { _, _ -> stop = true }) }
        assertTrue(s.keys("a", "rows").isEmpty()); assertTrue(s.keys("a", "pending").isEmpty())
        e.scan("a", "Notes", listOf(entry("a.md")), t, { ObsidianRead.Stable("") })
        e.closeDueWindows(t + 300_000)
        assertEquals("", lines(s).single().text)
    }
}

private class MemoryLedger : ObsidianLedger {
    private var records = mutableMapOf<Triple<String, String, String>, String>()
    private var prepared = mutableMapOf<Pair<String, String>, Pair<PreparedObsidianBatch, ByteArray>>()
    override fun <T> transaction(action: () -> T): T {
        val old = records.toMutableMap(); val oldPrepared = prepared.toMutableMap()
        return try { action() } catch (e: Throwable) { records = old; prepared = oldPrepared; throw e }
    }
    override fun get(root: String, bucket: String, key: String) = records[Triple(root, bucket, key)]
    override fun put(root: String, bucket: String, key: String, value: String) { records[Triple(root, bucket, key)] = value }
    override fun remove(root: String, bucket: String, key: String) { records.remove(Triple(root, bucket, key)) }
    override fun keys(root: String, bucket: String) = records.keys.filter { it.first == root && it.second == bucket }.map { it.third }
    override fun roots() = records.keys.map { it.first }.distinct()
    override fun prepare(batch: PreparedObsidianBatch, bytes: ByteArray) { prepared.putIfAbsent(batch.root to batch.name, batch to bytes.copyOf()) }
    override fun batches() = prepared.values.map { it.first }.sortedBy { it.keys.startEpochMs }
    override fun batchBytes(root: String, name: String) = prepared[root to name]?.second
    override fun markSpooled(root: String, name: String) { prepared[root to name]?.let { prepared[root to name] = it.first.copy(spooled = true) to it.second } }
    override fun retireBatch(root: String, name: String) { prepared.remove(root to name) }
}
