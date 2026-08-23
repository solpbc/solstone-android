// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WristShareTest {
    @Test
    fun unknownIsNotAZero() {
        assertNotEquals<WristShare>(WristShare.Unknown, WristShare.Known(0))
        assertEquals(0, (WristShare.Known(0) as WristShare.Known).count)
    }
}
