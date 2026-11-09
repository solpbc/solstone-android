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
        assertEquals("unknown", WatchSourcePageModel.formatLastContact(null, contact))
        assertEquals("just now", WatchSourcePageModel.formatLastContact(contact, contact + 1_000))
        val old = WatchSourcePageModel.formatLastContact(contact, contact + 8 * 86_400_000L)
        assertNotEquals("unknown", old)
        assertNotEquals(contact.toString(), old)
        assertTrue(old.contains("2026"))
    }
}
