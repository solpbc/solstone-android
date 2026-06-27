// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwtClaimsTest {
    @Test
    fun parsesValidUrlSafeJwtClaims() {
        val token = jwt("""{"iat":100,"exp":200}""")

        assertEquals(JwtClaims(iat = 100, exp = 200), parseJwtClaims(token))
    }

    @Test
    fun rejectsMalformedJwtShapes() {
        assertNull(parseJwtClaims("one"))
        assertNull(parseJwtClaims("one.two"))
        assertNull(parseJwtClaims("one.not-valid-base64.three"))
        assertNull(parseJwtClaims(jwt("123")))
        assertNull(parseJwtClaims(jwt("[]")))
        assertNull(parseJwtClaims(jwt("""{"iat":100}""")))
        assertNull(parseJwtClaims(jwt("""{"exp":200}""")))
        assertNull(parseJwtClaims(jwt("""{"iat":100,"exp":"200"}""")))
    }

    @Test
    fun convertsJsonDoublesToLongClaims() {
        val token = jwt("""{"iat":100.9,"exp":200.2}""")

        assertEquals(JwtClaims(iat = 100, exp = 200), parseJwtClaims(token))
    }

    @Test
    fun refreshesOnlyAfterStrictEightyPercentAge() {
        val token = jwt("""{"iat":100,"exp":200}""")

        assertFalse(shouldRefreshDeviceToken(token, nowEpochMs = 179_000L))
        assertFalse(shouldRefreshDeviceToken(token, nowEpochMs = 180_000L))
        assertTrue(shouldRefreshDeviceToken(token, nowEpochMs = 181_000L))
    }

    @Test
    fun doesNotRefreshInvalidTokenOrNonPositiveTtl() {
        assertFalse(shouldRefreshDeviceToken("not.jwt", nowEpochMs = 181_000L))
        assertFalse(shouldRefreshDeviceToken(jwt("""{"iat":200,"exp":200}"""), nowEpochMs = 300_000L))
        assertFalse(shouldRefreshDeviceToken(jwt("""{"iat":201,"exp":200}"""), nowEpochMs = 300_000L))
    }

    private fun jwt(payload: String): String =
        listOf("{}", payload, "sig")
            .map { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }
            .joinToString(".")
}
