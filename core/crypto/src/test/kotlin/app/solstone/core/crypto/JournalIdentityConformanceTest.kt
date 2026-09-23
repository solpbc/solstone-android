// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import app.solstone.core.pl.DirectPairLink
import app.solstone.core.pl.RelayPairLink
import app.solstone.core.pl.decodeCrockford32
import app.solstone.core.pl.parsePairLink
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
        val conformance = adoption.requiredMap("conformance")
        assertEquals(
            "core/crypto/src/test/kotlin/app/solstone/core/crypto/JournalIdentityConformanceTest.kt",
            conformance.requiredString("test"),
        )
        assertEquals(
            listOf("parse_pair_link", "derive_jid", "decode_crockford", "derive_relay_key"),
            conformance.requiredList("bound_operations").map { it as? String ?: error("expected operation string") },
        )
        assertTrue(conformance.requiredList("not_implemented").isEmpty())
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
    fun parsePairLinkVectorsMatchClientParser() {
        val vectors = loadVerifiedCorpus().vectors.filter { it.requiredString("operation") == "parse_pair_link" }
        assertEquals(73, vectors.size, "parse_pair_link vector count")
        val failures = vectors.mapNotNull(::pairLinkVectorFailure)
        assertTrue(
            failures.isEmpty(),
            "${failures.size} of ${vectors.size} parse_pair_link vectors failed: ${failures.joinToString(", ")}",
        )
    }

    @Test
    fun decodeCrockfordVectorMatchesClientDecoder() {
        val vector = loadVerifiedCorpus().vectors.single { it.requiredString("operation") == "decode_crockford" }
        assertEquals("pair.v04.canonical.decode", vector.requiredString("id"))
        assertContentEquals(
            hexBytes(vector.requiredString("expected_hex")),
            decodeCrockford32(vector.requiredString("input")),
        )
    }

    @Test
    fun deriveRelayKeyVectorMatchesClientImplementation() {
        val vector = loadVerifiedCorpus().vectors.single { it.requiredString("operation") == "derive_relay_key" }
        assertEquals("relay.rk.published", vector.requiredString("id"))
        assertContentEquals(
            hexBytes(vector.requiredString("expected_hex")),
            deriveRk(hexBytes(vector.requiredString("secret_hex"))),
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
        assertEquals(84, vectors.size, "total conformance vector count")
        assertOperationHistogramIsPinned(vectors)
        val deriveJidVectors = vectors.filter { it.requiredString("operation") == "derive_jid" }
        assertDeriveJidSelectionIsPinned(deriveJidVectors)
        return VerifiedCorpus(vectors)
    }

    private fun pairLinkVectorFailure(vector: Map<String, Any?>): String? {
        val id = vector.requiredString("id")
        val expected = vector.requiredMap("expected")
        val input = vector.requiredMap("input")
        val link = when (input.requiredString("encoding")) {
            "link" -> input.requiredString("value")
            "blob_hex" -> pairLinkFromBlob(hexBytes(input.requiredString("value")))
            else -> return "$id has an unrecognized input encoding"
        }
        val parsed = try {
            parsePairLink(link)
        } catch (error: IllegalArgumentException) {
            return if (expected.requiredString("result") == "error") {
                null
            } else {
                "$id expected " + expected.requiredString("result") + ", got " + error::class.simpleName + ": " + error.message
            }
        }
        if (expected.requiredString("result") == "error") {
            return "$id expected refusal, got " + parsed::class.simpleName
        }
        return when (expected.requiredString("result")) {
            "direct" -> {
                val direct = parsed as? DirectPairLink ?: return "$id expected direct, got " + parsed::class.simpleName
                val expectedCandidates = expected.requiredList("candidates").map { value ->
                    val candidate = value.requiredMap()
                    candidate.requiredString("host") + ":" + candidate.requiredLong("port")
                }
                val actualCandidates = direct.candidates.map { it.host + ":" + it.port }
                when {
                    actualCandidates != expectedCandidates -> "$id candidates expected $expectedCandidates, got $actualCandidates"
                    direct.nonce != expected.requiredString("nonce_hex") -> "$id nonce mismatch"
                    hex(direct.caFingerprintPrefix) != expected.requiredString("ca_fp_hex") -> "$id CA fingerprint mismatch"
                    else -> null
                }
            }
            "relay" -> {
                val relay = parsed as? RelayPairLink ?: return "$id expected relay, got " + parsed::class.simpleName
                val actualOrigin = relay.relayOrigin ?: WELL_KNOWN_RELAY_ORIGIN
                when {
                    hex(relay.s) != expected.requiredString("secret_hex") -> "$id secret mismatch"
                    hex(relay.caFpSpki) != expected.requiredString("ca_fp_spki_hex") -> "$id CA SPKI fingerprint mismatch"
                    actualOrigin != expected.requiredString("relay_origin") -> "$id relay origin expected " + expected.requiredString("relay_origin") + ", got $actualOrigin"
                    else -> null
                }
            }
            else -> "$id has an unrecognized expected result"
        }
    }

    private fun pairLinkFromBlob(bytes: ByteArray): String {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val encoded = StringBuilder()
        var buffer = 0
        var bitCount = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bitCount += 8
            while (bitCount >= 5) {
                bitCount -= 5
                encoded.append(alphabet[(buffer shr bitCount) and 0x1f])
                buffer = buffer and ((1 shl bitCount) - 1)
            }
        }
        if (bitCount > 0) {
            encoded.append(alphabet[(buffer shl (5 - bitCount)) and 0x1f])
        }
        return "https://go.solstone.app/p#" + encoded
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

    // The pinned histogram makes changed or newly added bundle operations fail closed.
    private fun assertOperationHistogramIsPinned(vectors: List<Map<String, Any?>>) {
        assertEquals(
            mapOf("parse_pair_link" to 73, "derive_jid" to 9, "derive_relay_key" to 1, "decode_crockford" to 1),
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

    private data class VerifiedCorpus(val vectors: List<Map<String, Any?>>) {
        val deriveJidVectors: List<Map<String, Any?>>
            get() = vectors.filter { it["operation"] == "derive_jid" }

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
        const val AUTHORITY_COMMIT = "bc0eec0ac4230df023abb0d88bee812358b3fe60"
        const val AUTHORITY_MANIFEST_PATH = "proto/definition/bundle/manifest.json"
        const val AUTHORITY_MANIFEST_SHA256 = "5dc0c160ed9781964de2c6debe0b6e93f6b9d72e040a2b614356d1355b26dc64"
        const val BUNDLE_SEMVER = "8.0.1"
        const val BUNDLE_SCHEMA_IDENTITY = "spl.pair-link-definition-bundle.schema.v1"
        const val ADOPTION_SCHEMA_VERSION = 1L
        const val CONSUMER_IDENTIFIER = "solpbc/solstone-android"
        const val WELL_KNOWN_RELAY_ORIGIN = "https://link.solstone.app"
    }
}
