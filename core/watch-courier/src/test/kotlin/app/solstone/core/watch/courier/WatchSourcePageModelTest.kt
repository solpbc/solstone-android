// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.core.watch.courier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WatchSourcePageModelTest {
    @Test fun retainsDatedContactEvenWhenItIsOld() {
        val contact = java.time.Instant.parse("2026-11-01T17:00:00Z").toEpochMilli()
        val never = WatchSourcePageModel.formatLastContact(null, contact)
        val recent = WatchSourcePageModel.formatLastContact(contact, contact + 1_000)
        val minutes = WatchSourcePageModel.formatLastContact(contact, contact + 12 * 60_000L)
        assertEquals(3, setOf(never, recent, minutes).size, "never, just now and minutes ago read differently")
        assertTrue(minutes.contains("12"))
        val old = WatchSourcePageModel.formatLastContact(contact, contact + 8 * 86_400_000L)
        assertNotEquals(never, old)
        assertNotEquals(contact.toString(), old)
        assertTrue(old.contains("2026"))
    }

    @Test fun theTileFormStaysShortPastAnHour() {
        val contact = java.time.Instant.parse("2026-11-01T17:00:00Z").toEpochMilli()
        val old = WatchSourcePageModel.formatLastContactShort(contact, contact + 3 * 86_400_000L)
        assertTrue(old.length <= 8, old)
        assertEquals(WatchSourcePageModel.formatLastContact(contact, contact + 60_000L), WatchSourcePageModel.formatLastContactShort(contact, contact + 60_000L))
    }
}
