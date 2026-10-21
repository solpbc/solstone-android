// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import android.os.SystemClock
import kotlin.test.Test
import kotlin.test.assertEquals

class RefusalSpacingClockTest {
    // The spacing between counted refusals must keep running while the phone sleeps. Falsified by
    // spacing on System.nanoTime (or uptime), which stops in deep sleep: in a cached process,
    // refusals hours apart would read as minutes apart and the seventh refusal's stop would drift.
    @Test
    fun refusalsAreSpacedOnTheClockThatCountsSleep() {
        assertEquals<Any>(SystemClock::elapsedRealtime, refusalSpacingClock)
    }
}
