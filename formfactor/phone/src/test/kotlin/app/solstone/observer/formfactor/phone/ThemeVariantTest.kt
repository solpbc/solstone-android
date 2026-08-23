// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeVariantTest {
    @Test
    fun bucketAndDarknessMapToSixVariants() {
        assertEquals(
            ThemeVariant.LIGHT_STANDARD,
            themeVariant(isDark = false, ContrastClass.STANDARD),
        )
        assertEquals(
            ThemeVariant.LIGHT_MEDIUM,
            themeVariant(isDark = false, ContrastClass.MEDIUM),
        )
        assertEquals(
            ThemeVariant.LIGHT_HIGH,
            themeVariant(isDark = false, ContrastClass.HIGH),
        )
        assertEquals(
            ThemeVariant.DARK_STANDARD,
            themeVariant(isDark = true, ContrastClass.STANDARD),
        )
        assertEquals(
            ThemeVariant.DARK_MEDIUM,
            themeVariant(isDark = true, ContrastClass.MEDIUM),
        )
        assertEquals(
            ThemeVariant.DARK_HIGH,
            themeVariant(isDark = true, ContrastClass.HIGH),
        )
    }
}
