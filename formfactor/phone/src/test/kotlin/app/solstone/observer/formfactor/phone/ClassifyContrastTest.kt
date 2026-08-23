// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals

class ClassifyContrastTest {
    @Test
    fun belowApi34IsStandardRegardlessOfContrast() {
        assertEquals(ContrastClass.STANDARD, classifyContrast(26, 1.0f))
        assertEquals(ContrastClass.STANDARD, classifyContrast(33, 1.0f))
        assertEquals(ContrastClass.STANDARD, classifyContrast(33, 0.5f))
    }

    @Test
    fun negativesAndDefaultMapToStandard() {
        assertEquals(ContrastClass.STANDARD, classifyContrast(34, -1.0f))
        assertEquals(ContrastClass.STANDARD, classifyContrast(34, 0.0f))
        assertEquals(ContrastClass.STANDARD, classifyContrast(34, 0.49f))
    }

    @Test
    fun mediumStopIsHalfInclusive() {
        assertEquals(ContrastClass.MEDIUM, classifyContrast(34, 0.5f))
        assertEquals(ContrastClass.MEDIUM, classifyContrast(34, 0.99f))
    }

    @Test
    fun highStopIsOneInclusive() {
        assertEquals(ContrastClass.HIGH, classifyContrast(34, 1.0f))
    }
}
