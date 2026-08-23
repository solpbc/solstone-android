// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneSurfaceTest {
    @Test
    fun paneTitlesAreUnique() {
        val titles = phoneSurfaces().map { it.paneTitle }
        assertEquals(titles.size, titles.distinct().size)
    }

    @Test
    fun surfaceCountIsEight() {
        assertEquals(8, phoneSurfaces().size)
    }

    @Test
    fun deckIsAMember() {
        assertTrue(PhoneDeck in phoneSurfaces())
    }
}
