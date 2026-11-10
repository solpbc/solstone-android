// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.platform.persistence.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.obsidian.ObsidianCounterOp
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class ObsidianIntakeDeviceTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "obsidian-intake-${UUID.randomUUID()}"
    private lateinit var db: SolstonePersistenceDatabase
    private fun open() = Room.databaseBuilder(ctx, SolstonePersistenceDatabase::class.java, name).allowMainThreadQueries().build()
    @Before fun setUp() { db = open() }
    @After fun tearDown() { db.close(); ctx.deleteDatabase(name) }
    private fun op(seq: Long, kind: String, path: String, previous: String? = null, at: Long = 1000, root: String = "one") =
        ObsidianCounterOp(root, seq, kind, path, previous, at)
    private fun seen(path: String, root: String = "one") = db.obsidianIntakeDao().seen("path:$root:$path")

    @Test fun baselineAddsNoDatesAndReplayAfterRenameAndPathReuseChangesNothing() {
        var dao = db.obsidianIntakeDao()
        dao.apply(listOf(op(1, "baseline", "old")), 4000)
        assertNull(dao.reading()); assertTrue(seen("old"))
        val new = op(2, "new", "A")
        dao.apply(listOf(new), 5000)
        assertEquals(SourceIntakeRow("obsidian", 1, 5000, 1000), dao.reading())
        val rename = op(3, "rename", "B", "A", 1500)
        dao.apply(listOf(rename), 6000)
        assertFalse(seen("A")); assertTrue(seen("B"))
        dao.apply(listOf(op(4, "new", "A", at = 2000)), 7000)
        db.close(); db = open(); dao = db.obsidianIntakeDao()
        // Replays can arrive after private retirement failed. They must not move the reused A.
        dao.apply(listOf(rename, new), 9000)
        assertTrue(seen("A")); assertTrue(seen("B"))
        assertEquals(SourceIntakeRow("obsidian", 2, 5000, 2000), dao.reading())
        dao.apply(listOf(op(1, "new", "A", at = 1500, root = "two")), 10000)
        assertTrue(seen("A", "two"))
        assertEquals(SourceIntakeRow("obsidian", 3, 5000, 2000), dao.reading())
        dao.apply(listOf(op(5, "new", "A", at = 9000)), 11000)
        assertEquals(SourceIntakeRow("obsidian", 3, 5000, 2000), dao.reading())
    }
    @Test fun failureAfterRoomWritesRollsBackMarkerSeenPathAndTotalTogether() {
        val dao = db.obsidianIntakeDao(); val first = op(1, "new", "A"); var checks = 0
        try {
            dao.apply(listOf(first), 5000) { ++checks < 2 }
            fail("expected transaction cancellation")
        } catch (_: CancellationException) { }
        assertNull(dao.reading()); assertFalse(seen("A")); assertTrue(dao.markers("op:one:", "op:one:~").isEmpty())
        dao.apply(listOf(first), 6000)
        assertEquals(SourceIntakeRow("obsidian", 1, 6000, 1000), dao.reading())
        dao.apply(listOf(first), 9000)
        assertEquals(SourceIntakeRow("obsidian", 1, 6000, 1000), dao.reading())
    }
    @Test fun aFailedRenameCannotLoseItsOldSeenIdentity() {
        val dao = db.obsidianIntakeDao()
        dao.apply(listOf(op(1, "new", "A")), 5000)
        var checks = 0
        try { dao.apply(listOf(op(2, "rename", "B", "A")), 6000) { ++checks < 2 }; fail("expected cancellation") }
        catch (_: CancellationException) { }
        assertTrue(seen("A")); assertFalse(seen("B"))
        assertEquals(SourceIntakeRow("obsidian", 1, 5000, 1000), dao.reading())
        dao.apply(listOf(op(2, "rename", "B", "A")), 7000)
        assertFalse(seen("A")); assertTrue(seen("B"))
        assertEquals(SourceIntakeRow("obsidian", 1, 5000, 1000), dao.reading())
    }
}
