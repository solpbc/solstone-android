// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaneStatesTest {
    @Test
    fun emptyHasNoPanesOpen() {
        PhonePane.entries.forEach { pane ->
            assertFalse(PaneStates.Empty.isOpen(pane))
        }
    }

    @Test
    fun openIsIdempotentAndCloseClears() {
        val opened = PaneStates.Empty.open(PhonePane.SHELF)
        val twice = opened.open(PhonePane.SHELF)
        assertTrue(opened.isOpen(PhonePane.SHELF))
        assertEquals(opened, twice)
        val closed = opened.close(PhonePane.SHELF)
        assertFalse(closed.isOpen(PhonePane.SHELF))
        assertEquals(PaneStates.Empty, closed.close(PhonePane.SHELF))
    }

    @Test
    fun everyPaneRoundTrips() {
        val panes = PhonePane.entries
        assertTrue(panes.isNotEmpty())
        assertEquals(phoneSurfaces().count { it is PhonePane }, panes.size)
        panes.forEach { pane ->
            assertEquals(pane, decodePhonePane(encodePhonePane(pane)))
        }
    }

    @Test
    fun encodeDecodeRoundTripsOpenPanes() {
        val states = PaneStates.Empty.open(PhonePane.STATUS).open(PhonePane.SHELF)
        assertEquals(listOf("pane-shelf", "pane-status"), encodePaneStates(states))
        assertEquals(states, decodePaneStates(encodePaneStates(states)))
    }

    @Test
    fun decodeDropsUnknownKeys() {
        val states = decodePaneStates(listOf("pane-journal", "nope", "pane-status"))
        assertFalse(states.isOpen(PhonePane.SHELF))
        assertTrue(states.isOpen(PhonePane.JOURNAL))
        assertTrue(states.isOpen(PhonePane.STATUS))
        assertEquals(PaneStates.Empty, decodePaneStates(listOf("x", "y")))
    }
}
