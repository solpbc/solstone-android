// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemePolarityTest {
    @Test
    fun lightGroundMapsToLightBars() {
        assertTrue(ThemeVariant.LIGHT_STANDARD.isLightGround)
        assertTrue(ThemeVariant.LIGHT_MEDIUM.isLightGround)
        assertTrue(ThemeVariant.LIGHT_HIGH.isLightGround)
        assertFalse(ThemeVariant.DARK_STANDARD.isLightGround)
        assertFalse(ThemeVariant.DARK_MEDIUM.isLightGround)
        assertFalse(ThemeVariant.DARK_HIGH.isLightGround)
    }
}
