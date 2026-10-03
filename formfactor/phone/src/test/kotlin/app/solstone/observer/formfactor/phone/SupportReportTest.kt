// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportReportTest {
    @Test
    fun reportUsesFixedFragmentContract() {
        val about = "android app 2.0.0 · android 16\njournal 1.2.3 · ubuntu 24.04 · x86_64"
        val url = supportReportUrl("2.0.0", "8", "16", "status unavailable", about)
        assertTrue(url.startsWith("https://support.solstone.app/#report=v1&app=solstone+for+android"))
        assertTrue(url.contains("&state=status+unavailable"))
        assertFalse(url.contains('?'))
        assertFalse(url.contains("device"))
        val fragment = url.substringAfter('#')
        val fields = fragment.split('&').associate { part ->
            val (key, value) = part.split('=', limit = 2)
            URLDecoder.decode(key, Charsets.UTF_8.name()) to URLDecoder.decode(value, Charsets.UTF_8.name())
        }
        assertEquals(about, fields["about"])
        assertFalse(fields.containsKey("journal"))
        assertTrue(fields.filterKeys { it != "about" }.values.none { it.contains("journal") })
        assertTrue(fields.getValue("about").contains("\n"))
        assertTrue(fields.getValue("about").contains('·'))
        assertEquals(fragment, supportReportFields("2.0.0", "8", "16", "status unavailable", about))
        assertFalse(fields.getValue("about").contains("nothing yet. this fills as the app runs."))
    }

    @Test
    fun optionalFieldsAreOmittedAndStateIsBounded() {
        val url = supportReportUrl("1", "2", "", "é".repeat(501), "android app\njournal unknown")
        assertFalse(url.contains("os_version="))
        assertTrue("%C3%A9".toRegex().findAll(url).count() == 500)
        val optional = supportReportUrl("1", "2", null, "", "android app\njournal unknown")
        assertFalse(optional.contains("state="))
        assertTrue(optional.contains("about="))
    }
}
