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
        assertTrue(titles.all { it.isNotBlank() })
        assertEquals(titles.size, titles.distinct().size)
        assertEquals(phoneSurfaces().size, titles.distinct().size)
    }

    @Test
    fun headingKeysAreUnique() {
        val keys = phoneSurfaces().map { it.headingKey }
        assertTrue(keys.all { it.isNotBlank() })
        assertEquals(keys.size, keys.distinct().size)
        assertEquals(phoneSurfaces().size, keys.distinct().size)
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
