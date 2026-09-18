// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JournalMarkStoreTest {
    @Test
    fun journalMarkDataStructuresHoldValues() {
        val icon1 = JournalMarkIcon(
            name = "piano",
            svg = "<path d=\"M0 0\"/>",
            colorName = "blue",
            colorHex = "#3b82f6",
            rot = 45,
        )
        val icon2 = JournalMarkIcon(
            name = "key",
            svg = "<path d=\"M0 0\"/>",
            colorName = "purple",
            colorHex = "#a855f7",
            rot = 0,
        )
        val mark = JournalMark(
            icon1 = icon1,
            icon2 = icon2,
            words = listOf("liquefy", "smock"),
        )
        val record = JournalMarkRecord(
            instanceId = "inst-1",
            mark = mark,
        )

        assertEquals("inst-1", record.instanceId)
        assertEquals(mark, record.mark)
        assertEquals(45, record.mark?.icon1?.rot)
        assertEquals(0, record.mark?.icon2?.rot)
    }

    @Test
    fun journalMarkPresentationHierarchy() {
        val generic = JournalMarkPresentation.Generic
        val unavailable = JournalMarkPresentation.Unavailable
        val identified = JournalMarkPresentation.Identified(
            JournalMark(
                icon1 = JournalMarkIcon("piano", "<path/>", "blue", "#3b82f6", 45),
                icon2 = JournalMarkIcon("key", "<path/>", "purple", "#a855f7", 0),
                words = listOf("liquefy", "smock"),
            ),
        )

        assertIs<JournalMarkPresentation>(generic)
        assertIs<JournalMarkPresentation>(unavailable)
        assertIs<JournalMarkPresentation>(identified)
    }
}
