// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.i18n

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JournalLocaleHeaderTest {
    @Test
    fun omitsEnglishAndPreservesNonEnglishTags() {
        assertNull(journalLocaleHeaderValue(Locale.US))
        assertNull(journalLocaleHeaderValue(Locale.ENGLISH))
        assertNull(journalLocaleHeaderValue(Locale.forLanguageTag("en-US")))
        assertNull(journalLocaleHeaderValue(Locale.forLanguageTag("en")))
        assertEquals("en-XA", journalLocaleHeaderValue(Locale.forLanguageTag("en-XA")))
        assertEquals("fr-FR", journalLocaleHeaderValue(Locale.FRANCE))
    }
}
