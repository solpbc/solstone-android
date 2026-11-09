// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchSourcePageModelTest {

    @Test
    fun formatsLastContact() {
        val now = 100_000_000L
        assertEquals("unknown", WatchSourcePageModel.formatLastContact(null, now))
        assertEquals("unknown", WatchSourcePageModel.formatLastContact(0L, now))
        assertEquals("unknown", WatchSourcePageModel.formatLastContact(-10L, now))

        // Fresh contact (< 24h)
        val fresh = now - 3600_000L
        assertEquals(fresh.toString(), WatchSourcePageModel.formatLastContact(fresh, now))

        // Stale contact (>= 24h)
        val stale = now - (24 * 3600_000L)
        assertEquals("unknown", WatchSourcePageModel.formatLastContact(stale, now))
    }
}
