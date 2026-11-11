// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.screenshots

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KnownFilesTest {
    private val photo = KnownFile("primary:DCIM/Camera/a b.jpg", 1_219_663L, 1_700L, KnownVerdict.NotScreenshot)
    private val shot = KnownFile("primary:Pictures/Screenshots/s.png", 158_258L, 1_800L, KnownVerdict.InJournal("journal one"))

    @Test
    fun aKnownFileRoundTrips() {
        assertEquals(mapOf(photo.documentId to photo, shot.documentId to shot), decodeKnown(encodeKnown(listOf(photo, shot))))
    }

    @Test
    fun aVerdictHoldsOnlyWhileTheSizeAndChangeTimeDoNotMove() {
        assertEquals(KnownVerdict.NotScreenshot, knownVerdict(photo, 1_219_663L, 1_700L))
        assertNull(knownVerdict(photo, 1_219_664L, 1_700L), "rewritten: read it again")
        assertNull(knownVerdict(photo, 1_219_663L, 1_701L), "touched: read it again")
        assertNull(knownVerdict(photo, null, 1_700L), "a provider that reports no size is always read")
        assertNull(knownVerdict(null, 1L, 1L))
    }
}
