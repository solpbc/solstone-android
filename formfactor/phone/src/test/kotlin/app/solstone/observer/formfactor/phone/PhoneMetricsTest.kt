// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneMetricsTest {
    @Test
    fun minimumTouchTargetIs48Dp() {
        assertEquals(48, MINIMUM_TOUCH_TARGET_DP)
    }
}
