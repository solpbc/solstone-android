// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneGreetingTest {
    @Test
    fun greetingForMapsHourBands() {
        assertEquals("good evening", greetingFor(4))
        assertEquals("good morning", greetingFor(5))
        assertEquals("good morning", greetingFor(11))
        assertEquals("good afternoon", greetingFor(12))
        assertEquals("good afternoon", greetingFor(16))
        assertEquals("good evening", greetingFor(17))
        assertEquals("good evening", greetingFor(0))
        assertEquals("good evening", greetingFor(23))
    }
}
