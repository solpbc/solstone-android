// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonTest {
    @Test
    fun parsesAndSerializesNestedValues() {
        val parsed = parseJson("""{"items":[{"name":"a\nb","ok":true,"n":12.5}],"none":null}""") as Map<*, *>
        val items = parsed["items"] as List<*>
        val first = items[0] as Map<*, *>
        assertEquals("a\nb", first["name"])
        assertEquals(true, first["ok"])
        assertEquals(12.5, first["n"])
        assertEquals("""{"items":[{"name":"a\nb","ok":true,"n":12.5}],"none":null}""", toJson(parsed))
    }

    @Test
    fun rejectsMalformedJson() {
        assertFailsWith<IllegalArgumentException> { parseJson("""{"x":]""") }
        assertFailsWith<IllegalArgumentException> { parseJson("01") }
    }
}
