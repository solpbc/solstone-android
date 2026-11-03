// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.crypto.jidFromSpkiDer
import app.solstone.core.identity.JournalMark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Android derivation against vectors printed by `spl_core::mark::mark_from_jid`. The generator
 * is `tools/mark-vectors`. A difference here means two clients would draw different marks for one
 * journal.
 */
class JournalMarkDerivationTest {
    private val vectors = parseJson(resource("journal-mark/vectors.json")) as Map<*, *>
    private val cases = (vectors["cases"] as List<*>).map { it as Map<*, *> }

    @Test
    fun everyVectorDerivesTheSameMarkAsSplCore() {
        assertTrue(cases.size >= 20, "only ${cases.size} vectors")
        for (case in cases) {
            val jid = case["jid"] as String
            assertEquals(expectedMark(case), journalMarkFromJid(jid), "jid $jid")
        }
    }

    @Test
    fun theReferenceKeysGiveTheSameJidAsSplCore() {
        val withKeys = cases.filter { it["spki_der_hex"] != null }
        assertEquals(4, withKeys.size)
        for (case in withKeys) {
            assertEquals(case["jid"], jidFromSpkiDer(decodeHex(case["spki_der_hex"] as String)))
        }
    }

    @Test
    fun theVectorsReachAHyphenatedWord() {
        assertTrue(cases.any { case -> expectedMark(case).words.any { '-' in it } })
    }

    // Saved marks are read back through this parser, so what it refuses would never be kept.
    @Test
    fun everyDerivedMarkSurvivesTheStoresParser() {
        for (case in cases) {
            val mark = case["mark"] as Map<*, *>
            val body = toJson(mapOf("committed" to true, "instance_id" to case["jid"], "mark" to mark))
            assertEquals(expectedMark(case), assertNotNull(parseIdentityResponse(body)?.mark))
        }
    }

    @Test
    fun aJidThatIsNotAUuidIsRefused() {
        assertFailsWith<IllegalArgumentException> { journalMarkFromJid("not-a-jid") }
        assertFailsWith<IllegalArgumentException> { journalMarkFromJid("zzzzzzzz-ef46-8e9c-913f-e49f0fe7d201") }
    }

    private fun expectedMark(case: Map<*, *>): JournalMark {
        val mark = case["mark"] as Map<*, *>
        val body = toJson(mapOf("committed" to true, "instance_id" to "vector", "mark" to mark))
        return assertNotNull(parseIdentityResponse(body)?.mark, "vector mark did not parse")
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing test resource $path" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun decodeHex(value: String): ByteArray =
        ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
