// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneNewTileHeadingTest {
    @Test
    fun headingTextMapsImportAndAddMore() {
        assertEquals("import", headingText(PhoneRoute.Import))
        assertEquals("add more", headingText(PhoneRoute.AddMore))
    }
}
