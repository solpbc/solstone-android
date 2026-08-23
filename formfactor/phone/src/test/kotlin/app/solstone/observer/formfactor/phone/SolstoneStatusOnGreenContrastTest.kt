// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertTrue

class SolstoneStatusOnGreenContrastTest {
    @Test
    fun eachVariantGreenClearsThreeToOneAgainstCreamAndDark() {
        for (variant in ThemeVariant.entries) {
            val green = statusOnGreen(variant)
            val cream = contrastRatio(green, SolstoneColors.surfaceCream)
            val dark = contrastRatio(green, SolstoneColors.surfaceDark)
            assertTrue(cream >= 3.0, "$variant vs cream $cream")
            assertTrue(dark >= 3.0, "$variant vs dark $dark")
        }
    }
}
