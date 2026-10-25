// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenDidNotStartTest {
    @Test
    fun aSessionThatDoesNotStartRecordsOneDiagnosticAndLeavesTheFailureReasonAlone() {
        var failedReason: String? = null
        val recorded = mutableListOf<Triple<String, String, String>>()
        recordScreenDidNotStart(started = true) { source, reason, material ->
            recorded += Triple(source, reason, material)
        }
        assertEquals(emptyList(), recorded)
        assertNull(failedReason)

        recordScreenDidNotStart(started = false) { source, reason, material ->
            recorded += Triple(source, reason, material)
        }
        assertEquals(listOf(Triple("screen", "screen did not start", "screen-start")), recorded)
        assertNull(failedReason)
    }
}
