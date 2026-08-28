// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JournalIdentityConformanceTest {
    @Test
    fun vendoredBundleIsByteExactAgainstManifest() {
        val bundle = verifiedBundle()
        assertEquals(BUNDLE_SEMVER, bundle.manifest.requiredString("bundle_semver"))
        assertEquals(BUNDLE_SCHEMA_IDENTITY, bundle.manifest.requiredString("bundle_schema_identity"))
    }

    @Test
    fun adoptionRecordAgreesWithPinnedAuthorityAndManifest() {
        val bundle = verifiedBundle()
        val adoption = resourceJson("conformance/adoption.json")
        assertEquals("AGPL-3.0-only", adoption.requiredString("spdx_license_identifier"))
        assertEquals(ADOPTION_SCHEMA_VERSION, adoption.requiredLong("adoption_schema_version"))
        assertEquals(CONSUMER_IDENTIFIER, adoption.requiredString("consumer_identifier"))
        assertEquals(AUTHORITY_REPOSITORY, adoption.requiredString("authority_repository"))
        assertEquals(AUTHORITY_COMMIT, adoption.requiredString("authority_commit"))
        assertEquals(AUTHORITY_MANIFEST_PATH, adoption.requiredString("authority_manifest_path"))
        assertEquals(AUTHORITY_MANIFEST_SHA256, adoption.requiredString("authority_manifest_sha256"))
        assertEquals(bundle.manifest.requiredString("bundle_semver"), adoption.requiredString("bundle_semver"))
        assertEquals(bundle.files.map { it.path to it.digest }, adoption.files("bundle_files").map { it.path to it.digest })
    }

    @Test
    fun identityMirrorMatchesManifestAndVectorCitations() {
        val bundle = verifiedBundle()
        val identity = requiredResource("conformance/proto-ref/identity.md")
        val source = bundle.manifest.requiredList("generator_inputs")
            .map { it.requiredMap() }
            .single { it.requiredString("role") == "normative_source_document" && it.requiredString("path") == "proto/identity.md" }
        assertEquals(source.requiredString("sha256"), sha256Hex(identity))

        for (vector in loadVerifiedCorpus().deriveJidVectors) {
            assertTrue(
                identity.toString(Charsets.UTF_8).contains(vector.requiredMap("citation").requiredString("marker")),
                "citation marker missing for ${vector.requiredString("id")}",
            )
        }
    }

    @Test
    fun deriveJidVectorsMatchExpectedOutcomes() {
        val corpus = loadVerifiedCorpus()
        // entry_digests are intentionally not verified: upstream authors them over definition.json
        // journal_identity.* entries, and no reproducible canonicalisation is documented. Bind them
        // if upstream documents that canonicalisation.
        val failures = corpus.deriveJidVectors.mapNotNull { vector ->
            val observed = observe(vector)
            observed?.let { "${vector.requiredString("id")} ($it)" }
        }
        assertTrue(
            failures.isEmpty(),
            "${failures.size} of ${corpus.deriveJidVectors.size} derive_jid vectors failed: ${failures.joinToString(", ")}",
        )
    }

    @Test
    fun canonicalAndCompressedVectorsDeriveTheSameJid() {
        val corpus = loadVerifiedCorpus()
        val canonical = jidFromSpkiDer(corpus.vector("identity.jid.canonical").spkiDer())
        val compressed = jidFromSpkiDer(corpus.vector("identity.jid.compressed-point").spkiDer())

        assertEquals(canonical, compressed)
    }

    @Test
    fun canonicalVectorReencodesByteExactly() {
        val vector = loadVerifiedCorpus().vector("identity.jid.canonical")

        assertTrue(vector.spkiDer().contentEquals(canonicalP256Spki(vector.spkiDer())))
    }

    private fun verifiedBundle(): VerifiedBundle {
        val manifestBytes = requiredResource("conformance/bundle/manifest.json")
        assertEquals(AUTHORITY_MANIFEST_SHA256, sha256Hex(manifestBytes), "authority manifest digest")
        val manifest = parseConformanceJson(manifestBytes.toString(Charsets.UTF_8)).requiredMap()
        val files = manifest.files("files")
        val expectedInventory = (files.map { it.path } + "manifest.json").toSortedSet()
        assertEquals(expectedInventory, bundleInventory())
        for (file in files) {
            assertEquals(file.digest, sha256Hex(requiredResource("conformance/bundle/${file.path}")), "digest for ${file.path}")
        }
        return VerifiedBundle(manifest, files)
    }

    private fun loadVerifiedCorpus(): VerifiedCorpus {
        verifiedBundle()
        val vectors = resourceJson("conformance/bundle/vectors.json").requiredList("vectors").map { it.requiredMap() }
        assertEquals(78, vectors.size, "total conformance vector count")
        assertOperationHistogramIsPinned(vectors)
        val deriveJidVectors = vectors.filter { it.requiredString("operation") == "derive_jid" }
        assertDeriveJidSelectionIsPinned(deriveJidVectors)
        return VerifiedCorpus(deriveJidVectors)
    }

    private fun assertDeriveJidSelectionIsPinned(deriveJidVectors: List<Map<String, Any?>>) {
        val expectedIds = setOf(
            "identity.jid.canonical",
            "identity.jid.compressed-point",
            "identity.jid.explicit-parameters",
            "identity.jid.malformed",
            "identity.jid.off-curve-point",
            "identity.jid.trailing-data",
            "identity.jid.unused-bits",
            "identity.jid.wrong-algorithm",
            "identity.jid.wrong-curve",
        )
        val actualIds = deriveJidVectors.map { it.requiredString("id") }.toSet()
        val failures = buildList {
            if (deriveJidVectors.size != 9) add("derive_jid vector count expected 9 but was ${deriveJidVectors.size}")
            if (actualIds != expectedIds) add("derive_jid vector ids expected $expectedIds but was $actualIds")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    // Only derive_jid is bound by this consumer today; the full histogram prevents silent corpus drift.
    private fun assertOperationHistogramIsPinned(vectors: List<Map<String, Any?>>) {
        assertEquals(
            mapOf("parse_pair_link" to 67, "derive_jid" to 9, "derive_relay_key" to 1, "decode_crockford" to 1),
            vectors.groupingBy { it.requiredString("operation") }.eachCount(),
            "conformance operation histogram",
        )
    }

    private fun observe(vector: Map<String, Any?>): String? {
        val expected = vector.requiredMap("expected")
        return if (expected.requiredString("result") == "jid") {
            val observed = runCatching { jidFromSpkiDer(vector.spkiDer()) }
                .fold(
                    onSuccess = { "jid $it" },
                    onFailure = { "refusal ${it::class.simpleName}" },
                )
            observed.takeUnless { it == "jid ${expected.requiredString("jid")}" }
                ?.let { "expected jid ${expected.requiredString("jid")}, got $it" }
        } else {
            runCatching { jidFromSpkiDer(vector.spkiDer()) }.fold(
                onSuccess = { "expected refusal, got jid $it" },
                onFailure = { null },
            )
        }
    }

    private fun bundleInventory(): Set<String> {
        val url = requireNotNull(javaClass.classLoader.getResource("conformance/bundle")) {
            "missing test resource directory conformance/bundle"
        }
        require(url.protocol == "file") { "test resource directory conformance/bundle was not a file URL: $url" }
        return Files.list(Paths.get(url.toURI())).use { stream ->
            stream.map { it.fileName.toString() }.toList().toSortedSet()
        }
    }

    private fun resourceJson(path: String): Map<String, Any?> =
        parseConformanceJson(requiredResource(path).toString(Charsets.UTF_8)).requiredMap()

    private fun requiredResource(path: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing test resource $path" }.use { it.readBytes() }

    private data class VerifiedBundle(val manifest: Map<String, Any?>, val files: List<FileDigest>)

    private data class VerifiedCorpus(val deriveJidVectors: List<Map<String, Any?>>) {
        fun vector(id: String): Map<String, Any?> = deriveJidVectors.single { it["id"] == id }
    }

    private data class FileDigest(val path: String, val digest: String)

    private fun Map<String, Any?>.files(key: String): List<FileDigest> =
        requiredList(key).map { value ->
            val file = value.requiredMap()
            FileDigest(file.requiredString("path"), file.requiredString("sha256"))
        }

    private fun Any?.requiredMap(): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return this as? Map<String, Any?> ?: error("expected JSON object")
    }

    private fun Map<String, Any?>.requiredMap(key: String): Map<String, Any?> = get(key).requiredMap()

    private fun Map<String, Any?>.requiredList(key: String): List<Any?> = get(key) as? List<Any?> ?: error("expected JSON array $key")

    private fun Map<String, Any?>.requiredString(key: String): String = get(key) as? String ?: error("expected JSON string $key")

    private fun Map<String, Any?>.requiredLong(key: String): Long = get(key) as? Long ?: error("expected JSON integer $key")

    private fun Map<String, Any?>.spkiDer(): ByteArray = hexBytes(requiredString("spki_der_hex"))

    private fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private companion object {
        const val AUTHORITY_REPOSITORY = "https://github.com/solpbc/spl"
        const val AUTHORITY_COMMIT = "138140b804582aa3e8ca501613d30bd0c9f485d4"
        const val AUTHORITY_MANIFEST_PATH = "proto/definition/bundle/manifest.json"
        const val AUTHORITY_MANIFEST_SHA256 = "d9dfb5a2ace5b804000874012454024874e290d9d16974bcac84d7367668e091"
        const val BUNDLE_SEMVER = "6.0.0"
        const val BUNDLE_SCHEMA_IDENTITY = "spl.pair-link-definition-bundle.schema.v1"
        const val ADOPTION_SCHEMA_VERSION = 1L
        const val CONSUMER_IDENTIFIER = "solpbc/solstone-android"
    }
}
