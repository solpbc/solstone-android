// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.identity.JournalMarkPresentation
import kotlin.test.Test
import kotlin.test.assertEquals

class ShellMarkPresentationTest {
    private val loaded = JournalMarkPresentation.Unavailable

    @Test
    fun anUnconfirmedPairingShowsTheGenericMark() {
        assertEquals(JournalMarkPresentation.Generic, shellMarkPresentation("p1", "p1", false, loaded))
        // Even before the mark for this pairing has loaded.
        assertEquals(JournalMarkPresentation.Generic, shellMarkPresentation("p1", null, false, loaded))
    }

    @Test
    fun aConfirmedPairingShowsItsLoadedMark() {
        assertEquals(loaded, shellMarkPresentation("p1", "p1", true, loaded))
    }

    @Test
    fun aConfirmedPairingWhoseMarkIsForAnotherPairingIsLoading() {
        assertEquals(JournalMarkPresentation.Loading, shellMarkPresentation("p2", "p1", true, loaded))
    }

    @Test
    fun noPairingPassesThePresentationThrough() {
        assertEquals(loaded, shellMarkPresentation<String>(null, null, false, loaded))
    }
}
