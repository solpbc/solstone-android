// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.metadata

import kotlin.test.Test
import kotlin.test.assertEquals

class BatteryParsersTest {
    @Test
    fun parseBatteryTemperatureUsesPresenceNotSentinel() {
        assertEquals(-5.0, parseBatteryTemperatureC(present = true, tenthsC = -50))
        assertEquals(null, parseBatteryTemperatureC(present = false, tenthsC = -50))
        assertEquals(-0.1, parseBatteryTemperatureC(present = true, tenthsC = -1))
    }
}
