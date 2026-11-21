// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.pl.SharedSourceRow
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedSourceLineTest {
    private val now = Instant.parse("2026-11-20T12:00:00Z")
    private val row = SharedSourceRow("field notes", "needs_attention", now.minusSeconds(240))

    @Test
    fun aDrawnLineBreaksOnlyAfterASeparatorOrInsideTheName() {
        val name = "work laptop"
        val drawn = row.line(name, now.toEpochMilli())
        val rest = drawn.removePrefix(name)
        val breaks = rest.indices.filter { rest[it] == ' ' }
        assertTrue(breaks.isNotEmpty(), drawn)
        // Past the name, every plain space follows a separator, and no separator starts a line.
        assertTrue(breaks.all { it > 0 && rest[it - 1] == '·' }, drawn)
        assertFalse(" ·" in drawn, drawn)
    }

    @Test
    fun theTileNameCannotBreakAndTheSpokenLineIsPlain() {
        val drawn = row.line(unbroken("work laptop"), now.toEpochMilli())
        val spoken = row.line("work laptop", now.toEpochMilli(), spoken = true)
        assertEquals(drawn.count { it == ' ' }, drawn.count { it == '·' })
        assertFalse(' ' in spoken, spoken)
    }

    @Test
    fun theLayoutCheckAcceptsOnlyBreaksAfterASeparator() {
        val text = "work laptop · needs attention · 4 min ago"
        val afterFirst = text.indexOf("· ") + 2
        assertTrue(breaksOnlyAtSeparators(text, listOf(afterFirst, text.length)))
        assertFalse(breaksOnlyAtSeparators(text, listOf(afterFirst + 3, text.length)))
        assertTrue(breaksOnlyAtSeparators(text, listOf(text.length)))
    }
}
