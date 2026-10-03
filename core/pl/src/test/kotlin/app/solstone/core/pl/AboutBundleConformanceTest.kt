// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AboutBundleConformanceTest {
    @Test
    fun adoptionRecordAndBundleAgreeWithPinnedAuthority() {
        val manifestBytes = requiredResource("about/bundle/manifest.json")
        assertEquals(MANIFEST_SHA256, sha256Hex(manifestBytes))
        val manifest = parseJson(manifestBytes.toString(Charsets.UTF_8)).requiredMap()
        val adoption = resourceJson("about/adoption.json")

        assertEquals("AGPL-3.0-only", adoption.requiredString("spdx_license_identifier"))
        assertEquals(1L, adoption.requiredLong("adoption_schema_version"))
        assertEquals("solpbc/solstone-android", adoption.requiredString("consumer_identifier"))
        assertEquals(AUTHORITY_COMMIT, adoption.requiredString("authority_commit"))
        assertEquals(MANIFEST_SHA256, adoption.requiredString("authority_manifest_sha256"))
        assertFalse("bundle_semver" in adoption)
        assertEquals(manifest.requiredString("bundle_version"), adoption.requiredString("bundle_version"))
        assertEquals(manifest.requiredMap("inputs"), adoption.requiredMap("inputs"))

        val artifacts = manifest.requiredMap("artifacts").mapValues { (_, value) -> value as String }
        val adoptionFiles = adoption.requiredList("bundle_files").map { value ->
            val file = value.requiredMap()
            file.requiredString("path") to file.requiredString("sha256")
        }.toMap()
        assertEquals((artifacts + ("manifest.json" to MANIFEST_SHA256)).toSortedMap(), adoptionFiles.toSortedMap())
        for ((path, digest) in artifacts) {
            assertEquals(digest, sha256Hex(requiredResource("about/bundle/$path")), "digest for $path")
        }
        assertEquals(MANIFEST_SHA256, adoptionFiles["manifest.json"])

        val conformance = adoption.requiredMap("conformance")
        assertEquals("core/pl/src/test/kotlin/app/solstone/core/pl/AboutBundleConformanceTest.kt", conformance.requiredString("test"))
        assertEquals(listOf("render_line", "normalize_arch", "decode_about"), conformance.requiredList("bound_operations"))
        assertEquals(listOf("host_about", "parse_os_release", "windows_version", "native_envelope"), conformance.requiredList("not_implemented"))
    }

    private fun resourceJson(path: String): Map<String, Any?> =
        parseJson(requiredResource(path).toString(Charsets.UTF_8)).requiredMap()

    private fun requiredResource(path: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing test resource $path" }.use { it.readBytes() }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun Any?.requiredMap(): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return this as? Map<String, Any?> ?: error("expected JSON object")
    }

    private fun Map<String, Any?>.requiredMap(key: String): Map<String, Any?> = get(key).requiredMap()
    private fun Map<String, Any?>.requiredList(key: String): List<Any?> = get(key) as? List<Any?> ?: error("expected JSON array $key")
    private fun Map<String, Any?>.requiredString(key: String): String = get(key) as? String ?: error("expected JSON string $key")
    private fun Map<String, Any?>.requiredLong(key: String): Long = exactJsonInteger(get(key)) ?: error("expected JSON integer $key")

    private companion object {
        const val MANIFEST_SHA256 = "301c1d84616379e11aaf22bb341e524bb4b2317051ea08453db9fdd87c706ab0"
        const val AUTHORITY_COMMIT = "ec1983799b66d3616708851d01803e4f3d6f0a20"
    }
}
