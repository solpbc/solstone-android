// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportReportTest {
    @Test
    fun reportUsesFixedFragmentContract() {
        val url = supportReportUrl("2.0.0", "8", "16", "status unavailable")
        assertTrue(url.startsWith("https://support.solstone.app/#report=v1&app=solstone+for+android"))
        assertTrue(url.contains("&state=status+unavailable"))
        assertFalse(url.contains('?'))
        assertFalse(url.contains("device"))
        assertFalse(url.contains("journal"))
    }

    @Test
    fun optionalFieldsAreOmittedAndStateIsBounded() {
        val url = supportReportUrl("1", "2", "", "é".repeat(501))
        assertFalse(url.contains("os_version="))
        assertTrue("%C3%A9".toRegex().findAll(url).count() == 500)
        assertFalse(supportReportUrl("1", "2", null, "").contains("state="))
    }
}
