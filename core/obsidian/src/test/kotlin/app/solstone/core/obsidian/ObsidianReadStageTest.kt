// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.core.obsidian

import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.CancellationException
import kotlin.test.*

class ObsidianReadStageTest {
    private val zone = ZoneId.of("America/Denver")
    private val t = OffsetDateTime.parse("2026-11-08T09:42:33-07:00").toInstant().toEpochMilli()
    private fun entry(path: String, at: Long? = null) = ObsidianEntry(path, at, null, path)
    private fun lines(s: MemoryLedger) = s.batches().flatMap { b ->
        requireNotNull(s.batchBytes(b.root, b.name)).toString(Charsets.UTF_8).lineSequence()
            .filter(String::isNotEmpty).map(::decodeObsidianChange).toList()
    }
    @Test fun boundedUnknownMetadataReadsReachTheTailAndRetainTheirOriginalTime() {
        val s = MemoryLedger(); val e = ObsidianEngine(s, zone)
        val entries = (1..6).map { entry("$it.md") }
        val calls = mutableListOf<String>()
        repeat(3) { pass ->
            val stage = ObsidianReadStage(s, "a"); var stop = false; var read = 0
            try { stage.fill(e.entriesNeedingRead("a", entries), { calls += it.path; read++; ObsidianRead.Stable("${it.path} text") },
                { t + pass * 1000 }, { stop }, { _, _ -> if (read == 2) stop = true }) }
            catch (_: CancellationException) { }
            assertTrue(s.keys("a", "rows").isEmpty()); assertTrue(s.keys("a", "pending").isEmpty())
        }
        assertEquals(entries.map { it.path }, calls)
        val stage = ObsidianReadStage(s, "a")
        e.scan("a", "Notes", entries, t + 600_000, stage::read, readStageToken = stage.token)
        e.closeDueWindows(t + 1_000_000)
        assertEquals(6, lines(s).size)
        assertEquals(obsidianStamp(t, zone), lines(s).first().observedAt)
        assertEquals(obsidianStamp(t, zone), lines(s).first().changedAt)
        assertNull(s.get("a", "meta", "notes_total"))
        // Simulate a crash after application but before stage cleanup.
        val next = ObsidianReadStage(s, "a"); assertNotEquals(stage.token, next.token)
        var reread = 0
        next.fill(e.entriesNeedingRead("a", entries), { reread++; ObsidianRead.Stable("new text") }, { t + 1_100_000 })
        assertEquals(6, reread)
        stage.clearApplied() // a stale owner cannot clear the next stage's reads
        assertEquals("new text", (next.read(entries.first()) as ObsidianRead.Stable).text)
        assertFails { stage.fill(entries, { error("must not read") }, { t }) }
    }
    @Test fun engineFailureRetainsUnappliedReadsWithoutPartialReportedState() {
        val s = MemoryLedger(); val e = ObsidianEngine(s, zone); val one = entry("a.md")
        val stage = ObsidianReadStage(s, "a")
        stage.fill(listOf(one), { ObsidianRead.Stable("text") }, { t })
        s.refuseNextBucket = "rows"
        assertFails { e.scan("a", "Notes", listOf(one), t, stage::read, readStageToken = stage.token) }
        assertTrue(s.keys("a", "rows").isEmpty()); assertTrue(s.keys("a", "pending").isEmpty())
        assertNull(s.get("a", "meta", "read_stage_applied"))
        val resumed = ObsidianReadStage(s, "a"); assertEquals(stage.token, resumed.token)
        e.scan("a", "Notes", listOf(one), t, resumed::read, readStageToken = resumed.token)
        e.closeDueWindows(t + 300_000)
        assertEquals(listOf("text"), lines(s).map { it.text })
    }
    @Test fun unreadBaselineRenameWithoutIdentityRemainsBaseline() {
        val s = MemoryLedger(); val e = ObsidianEngine(s, zone)
        val initial = listOf(entry("A.md"), entry("Z.md"))
        val stage = ObsidianReadStage(s, "a"); var stop = false
        assertFailsWith<CancellationException> { stage.fill(initial, { ObsidianRead.Stable("body") }, { t }, { stop }, { _, _ -> stop = true }) }
        val latest = listOf(entry("A.md"), entry("B.md"))
        val resumed = ObsidianReadStage(s, "a")
        resumed.fill(e.entriesNeedingRead("a", latest), { ObsidianRead.Stable("renamed unread body") }, { t + 400_000 })
        e.scan("a", "Notes", latest, t + 500_000, resumed::read, readStageToken = resumed.token)
        e.closeDueWindows(t + 1_000_000)
        assertEquals(setOf("A.md", "B.md"), lines(s).map { it.path }.toSet())
        assertNull(s.get("a", "meta", "notes_total"))
        assertNull(s.get("a", "rows", "Z.md"))
    }
    @Test fun stagedOldPathCannotBecomePresentAfterRenameOrDeletion() {
        val s = MemoryLedger(); val e = ObsidianEngine(s, zone)
        e.scan("a", "Notes", listOf(entry("A.md")), t, { ObsidianRead.Stable("old", "inode") })
        e.closeDueWindows(t + 300_000)
        val stage = ObsidianReadStage(s, "a")
        stage.fill(listOf(entry("A.md")), { ObsidianRead.Stable("discarded cached edit", "inode") }, { t + 400_000 })
        val latest = listOf(entry("B.md"))
        stage.fill(e.entriesNeedingRead("a", latest), { ObsidianRead.Stable("old", "inode") }, { t + 500_000 })
        e.scan("a", "Notes", latest, t + 600_000, stage::read, readStageToken = stage.token)
        e.closeDueWindows(t + 1_000_000)
        assertEquals("renamed", lines(s).last().change); assertEquals("A.md", lines(s).last().previousPath)
        assertFalse(lines(s).any { it.text == "discarded cached edit" })
        stage.clearApplied()
        val deletion = ObsidianReadStage(s, "a")
        deletion.fill(listOf(entry("B.md")), { ObsidianRead.Stable("also discarded") }, { t + 1_100_000 })
        e.scan("a", "Notes", emptyList(), t + 1_200_000, deletion::read, readStageToken = deletion.token)
        e.scan("a", "Notes", emptyList(), t + 1_300_000, deletion::read, readStageToken = deletion.token)
        e.closeDueWindows(t + 1_700_000)
        assertEquals(2, lines(s).size) // one token cannot be two absence observations
        deletion.clearApplied()
        val second = ObsidianReadStage(s, "a")
        e.scan("a", "Notes", emptyList(), t + 1_800_000, second::read, readStageToken = second.token)
        e.closeDueWindows(t + 2_300_000)
        assertEquals(listOf("changed", "renamed", "removed"), lines(s).map { it.change })
    }
    @Test fun aStagedLinkRewriteCannotFreezeBeforeItsRenameIsDiscovered() {
        val s = MemoryLedger(); val e = ObsidianEngine(s, zone)
        e.scan("a", "Notes", listOf(entry("link.md"), entry("old.md")), t,
            { ObsidianRead.Stable(if (it.path == "old.md") "note" else "[[old]]", it.path) })
        e.closeDueWindows(t + 300_000)
        val latest = listOf(entry("link.md", t + 400_000), entry("new.md", t - 1000))
        val stage = ObsidianReadStage(s, "a"); var stop = false
        assertFailsWith<CancellationException> { stage.fill(latest, { ObsidianRead.Stable("[[new]]", "link.md") },
            { t + 401_000 }, { stop }, { _, _ -> stop = true }) }
        e.closeDueWindows(t + 900_000)
        assertEquals(2, lines(s).size)
        val resumed = ObsidianReadStage(s, "a")
        resumed.fill(e.entriesNeedingRead("a", latest), { ObsidianRead.Stable("note", "old.md") }, { t + 410_000 })
        e.scan("a", "Notes", latest, t + 1_000_000, resumed::read, readStageToken = resumed.token)
        e.closeDueWindows(t + 1_500_000)
        val rename = lines(s).single { it.change == "renamed" }
        val rewrite = lines(s).single { it.text == "[[new]]" }
        assertEquals(obsidianWindowId(rename.changedAt), obsidianWindowId(rewrite.changedAt))
        assertTrue(OffsetDateTime.parse(rename.changedAt).toInstant() <= OffsetDateTime.parse(rewrite.changedAt).toInstant())
        assertEquals(obsidianStamp(t + 401_000, zone), rewrite.observedAt)
    }
}
