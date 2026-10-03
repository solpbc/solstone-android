// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AboutLineTest {
    @Test
    fun contractFixturesRenderExactly() {
        val contract = resourceJson("about/bundle/contract.json")
        val fixtures = contract.requiredList("fixtures")
        assertEquals(8, fixtures.size)
        fixtures.forEach { value ->
            val fixture = value.requiredMap()
            assertEquals(
                fixture.requiredString("about"),
                renderLine(
                    name = "journal",
                    version = fixture.requiredString("version"),
                    build = fixture["build"] as? String,
                    os = fixture.requiredString("os"),
                    osVersion = fixture.requiredString("os_version"),
                    arch = fixture.requiredString("arch"),
                ),
            )
        }
    }

    @Test
    fun architectureNormalizationIsExactAndCaseSensitive() {
        assertEquals("arm64", normalizeArch("arm64-v8a"))
        assertEquals("arm64", normalizeArch("aarch64"))
        assertEquals("arm64", normalizeArch("ARM64"))
        assertEquals("arm64", normalizeArch("arm64"))
        assertEquals("x86_64", normalizeArch("amd64"))
        assertEquals("x86_64", normalizeArch("x64"))
        assertEquals("x86_64", normalizeArch("AMD64"))
        assertEquals("x86_64", normalizeArch("x86_64"))
        assertEquals("riscv64", normalizeArch("riscv64"))
        assertEquals("Arm64", normalizeArch("Arm64"))
    }

    @Test
    fun emptyHostFactsAreOmittedAndNativeArchDoesNotUseAbiFallback() {
        assertEquals("journal 1.2.3", renderLine("journal", "1.2.3", null, "", "24.04", ""))
        assertEquals("journal 1.2.3 · ubuntu 24.04", renderLine("journal", "1.2.3", null, "ubuntu", "24.04", ""))
        assertNull(nativeArchForAbout(null, "arm64-v8a"))
        assertNull(nativeArchForAbout("  ", "arm64-v8a"))
        assertEquals("arm64", nativeArchForAbout("aarch64", "x86_64"))
    }

    @Test
    fun versionAndOptionalBuildRenderAccordingToPublisherRules() {
        assertEquals("android app 2.1.15 (24)", renderLine("android app", "vv2.1.15", "24", "", "", ""))
        assertEquals("android app", renderLine("android app", "vv", null, "", "", ""))
        assertEquals("journal 1.2.3 · ubuntu 24.04 · arm64", renderLine("journal", "v1.2.3", "", "ubuntu", "24.04", "aarch64"))
    }

    @Test
    fun freshnessPhrasesUseClosedBucketsAndOnlyLastKnownReadings() {
        val base = 1_000_000L
        fun suffix(age: Long, freshness: JournalVersionFreshness = JournalVersionFreshness.LAST_KNOWN, seenAt: Long? = base) =
            freshnessSuffix(freshness, seenAt, base + age)

        assertEquals(" · last seen just now", suffix(89_999L))
        assertEquals(" · last seen 1 minute ago", suffix(90_000L))
        assertEquals(" · last seen 1 hour ago", suffix(3_600_000L))
        assertEquals(" · last seen 3 hours ago", suffix(3 * 3_600_000L))
        assertEquals(" · last seen 1 day ago", suffix(86_400_000L))
        assertEquals(" · last seen 2 days ago", suffix(2 * 86_400_000L))
        assertEquals("", suffix(-1L))
        assertEquals("", freshnessSuffix(JournalVersionFreshness.LAST_KNOWN, null, base))
        assertEquals("", freshnessSuffix(JournalVersionFreshness.CURRENT, base, base + 86_400_000L))

        val legacy = JournalVersionReading("2.0.29", JournalVersionFreshness.LAST_KNOWN)
        assertEquals("journal 2.0.29", journalDisplayLine(legacy, base + 2 * 86_400_000L))
        val twoDays = legacy.copy(versionSeenAt = base)
        assertEquals("journal 2.0.29 · last seen 2 days ago", journalDisplayLine(twoDays, base + 2 * 86_400_000L))
        assertEquals("journal 2.0.29", journalDisplayLine(twoDays.copy(versionSeenAt = base + 1L), base))
        assertEquals("journal unknown", journalDisplayLine(null, base))
        assertEquals("journal unknown", journalDisplayLine(JournalVersionReading(" ", JournalVersionFreshness.LAST_KNOWN), base))
        assertEquals("journal unknown", journalDisplayLine(JournalVersionReading("1.2.3", JournalVersionFreshness.NEVER_OBSERVED), base))
        assertFalse(journalDisplayLine(null, base).contains(" · last seen "))
    }

    @Test
    fun aboutBlockHasExactlyTwoLfSeparatedLinesAndStoredLineIsGapHonest() {
        val storedLine = journalDisplayLine(
            JournalVersionReading("1.2.3", JournalVersionFreshness.CURRENT, os = "ubuntu", osVersion = "24.04", arch = "x86_64"),
            1L,
        )
        assertTrue(storedLine.matches(Regex("^journal [^\\r\\n]+$")))
        assertFalse(storedLine.contains(" · last seen "))

        val block = aboutBlock("android app 2.1.15 · android", storedLine)
        assertEquals(1, block.count { it == '\n' })
        assertFalse(block.contains('\r'))
    }

    private fun resourceJson(path: String): Map<String, Any?> =
        parseJson(requiredResource(path).toString(Charsets.UTF_8)).requiredMap()

    private fun requiredResource(path: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing test resource $path" }.use { it.readBytes() }

    private fun Any?.requiredMap(): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return this as? Map<String, Any?> ?: error("expected JSON object")
    }

    private fun Map<String, Any?>.requiredList(key: String): List<Any?> = get(key) as? List<Any?> ?: error("expected JSON array $key")
    private fun Map<String, Any?>.requiredString(key: String): String = get(key) as? String ?: error("expected JSON string $key")
}
