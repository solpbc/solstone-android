// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.push

import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PushKeyAccess
import java.io.File
import java.math.BigDecimal
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushPresentationTest {

    private fun findIrFile(): File {
        var current: File? = File(System.getProperty("user.dir"))
        while (current != null) {
            val candidate = File(current, "contracts/push-i18n/production/ir.json")
            if (candidate.exists()) return candidate
            current = current.parentFile
        }
        error("contracts/push-i18n/production/ir.json not found")
    }

    private val catalog by lazy {
        parsePushCatalog(findIrFile().readText())
    }

    @Test
    fun directApplyPresentationCardinalQuantities() {
        val calls = mutableListOf<PushRenderCall>()
        val render: (PushRenderCall) -> String = { call ->
            calls += call
            "SENTINEL_RENDER_${call.quantity}"
        }

        for (q in listOf(0, 1, 2, 10000)) {
            calls.clear()
            val presentation = mapOf(
                "body" to mapOf(
                    "v" to BigDecimal.ONE,
                    "catalog_epoch" to BigDecimal.ONE,
                    "code" to "push.proof.count",
                    "args" to mapOf("count" to BigDecimal(q)),
                    "fallback_en" to "SENTINEL_FALLBACK",
                ),
            )
            val (title, body) = applyPresentation("LEGACY_T", "LEGACY_B", presentation, catalog, render)
            assertEquals("LEGACY_T", title)
            assertEquals("SENTINEL_RENDER_$q", body)
            assertEquals(1, calls.size)
            val call = calls.single()
            assertEquals("push.proof.count", call.messageId)
            assertTrue(call.isCardinal)
            assertEquals(q, call.quantity)
            assertEquals("ungrouped", call.numericStyle)
        }
    }

    @Test
    fun directApplyPresentationInvalidCardinalArgsUseFallback() {
        var rendered = false
        val render: (PushRenderCall) -> String = {
            rendered = true
            "SENTINEL_RENDER"
        }

        val invalidCounts = listOf(
            BigDecimal("1.0"),
            BigDecimal("1.5"),
            true,
            BigDecimal("10001"),
            BigDecimal("-1"),
        )

        for (badCount in invalidCounts) {
            rendered = false
            val presentation = mapOf(
                "body" to mapOf(
                    "v" to BigDecimal.ONE,
                    "catalog_epoch" to BigDecimal.ONE,
                    "code" to "push.proof.count",
                    "args" to mapOf("count" to badCount),
                    "fallback_en" to "SENTINEL_FALLBACK",
                ),
            )
            val (_, body) = applyPresentation("LEGACY_T", "LEGACY_B", presentation, catalog, render)
            assertEquals("SENTINEL_FALLBACK", body)
            assertEquals(false, rendered, "Render must not be called for count=$badCount")
        }

        // Missing arg
        rendered = false
        val missingArgPres = mapOf(
            "body" to mapOf(
                "v" to BigDecimal.ONE,
                "catalog_epoch" to BigDecimal.ONE,
                "code" to "push.proof.count",
                "args" to emptyMap<String, Any?>(),
                "fallback_en" to "SENTINEL_FALLBACK",
            ),
        )
        val (_, bodyMissing) = applyPresentation("LEGACY_T", "LEGACY_B", missingArgPres, catalog, render)
        assertEquals("SENTINEL_FALLBACK", bodyMissing)
        assertEquals(false, rendered)

        // Extra arg
        rendered = false
        val extraArgPres = mapOf(
            "body" to mapOf(
                "v" to BigDecimal.ONE,
                "catalog_epoch" to BigDecimal.ONE,
                "code" to "push.proof.count",
                "args" to mapOf("count" to BigDecimal.ZERO, "extra" to "val"),
                "fallback_en" to "SENTINEL_FALLBACK",
            ),
        )
        val (_, bodyExtra) = applyPresentation("LEGACY_T", "LEGACY_B", extraArgPres, catalog, render)
        assertEquals("SENTINEL_FALLBACK", bodyExtra)
        assertEquals(false, rendered)
    }

    @Test
    fun directApplyPresentationUnknownEnumCallsRender() {
        var callRecord: PushRenderCall? = null
        val render: (PushRenderCall) -> String = { call ->
            callRecord = call
            "SENTINEL_RENDER_ENUM"
        }

        val presentation = mapOf(
            "body" to mapOf(
                "v" to BigDecimal.ONE,
                "catalog_epoch" to BigDecimal.ONE,
                "code" to "push.proof.state",
                "args" to mapOf("state" to "nope"),
                "fallback_en" to "SENTINEL_FALLBACK",
            ),
        )
        val (_, body) = applyPresentation("LEGACY_T", "LEGACY_B", presentation, catalog, render)
        assertEquals("SENTINEL_RENDER_ENUM", body)
        assertEquals("nope", callRecord?.enumArg)
        assertEquals("push.proof.state", callRecord?.messageId)
    }

    @Test
    fun directApplyPresentationUnknownIdUsesFallback() {
        var rendered = false
        val render: (PushRenderCall) -> String = {
            rendered = true
            "SENTINEL_RENDER"
        }

        val presentation = mapOf(
            "title" to mapOf(
                "v" to BigDecimal.ONE,
                "catalog_epoch" to BigDecimal.ONE,
                "code" to "push.unknown.message",
                "args" to emptyMap<String, Any?>(),
                "fallback_en" to "SENTINEL_FALLBACK",
            ),
        )
        val (title, _) = applyPresentation("LEGACY_T", "LEGACY_B", presentation, catalog, render)
        assertEquals("SENTINEL_FALLBACK", title)
        assertEquals(false, rendered)
    }

    private fun sealTestEnvelope(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        version: Byte = 0x01.toByte(),
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(byteArrayOf(version))
        val ciphertextAndTag = cipher.doFinal(plaintext)
        return byteArrayOf(version) + nonce + ciphertextAndTag
    }

    private fun padPlaintext(json: String): ByteArray {
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.size < 1024) { "json too large for budget: ${bytes.size}" }
        val pad = ByteArray(1024 - bytes.size)
        pad[0] = 0x80.toByte()
        return bytes + pad
    }

    private class RecordingNotifier : PushNotifier {
        data class Post(val id: Int, val title: String, val body: String, val open: String?)
        val posts = mutableListOf<Post>()
        override fun post(id: Int, title: String, body: String, open: String?) {
            posts += Post(id, title, body, open)
        }
    }

    @Test
    fun handlerPresentationSealedEnvelopeMatrix() {
        val key = ByteArray(32) { 0x42.toByte() }
        val nonce = ByteArray(12) { 0x13.toByte() }
        val gen = PairingGeneration("inst-1", "cert-1")
        val keys = object : PushKeyAccess {
            override fun readPushKey(generation: PairingGeneration): ByteArray? = key
            override fun obtainPushKey(generation: PairingGeneration) = error("unused")
        }

        val renderCalls = mutableListOf<PushRenderCall>()
        val presentFn: (String, String, Any?) -> Pair<String, String> = { title, body, pres ->
            applyPresentation(title, body, pres, catalog) { call ->
                renderCalls += call
                "SENTINEL_CATALOG_${call.messageId}"
            }
        }

        // Case 1: No presentation key
        run {
            renderCalls.clear()
            val notifier = RecordingNotifier()
            val handler = PushMessageHandler(keys, { gen }, notifier, log = {}, present = presentFn)
            val json = """{"v":1,"title":"LEGACY_T","body":"LEGACY_B","open":"/app/open"}"""
            val env = sealTestEnvelope(key, nonce, padPlaintext(json))
            handler.onMessage(env, true)
            val post = notifier.posts.single()
            assertEquals("LEGACY_T", post.title)
            assertEquals("LEGACY_B", post.body)
            assertEquals("/app/open", post.open)
            assertTrue(renderCalls.isEmpty())
        }

        // Case 2: Known push.test.title and push.test.body with empty args
        run {
            renderCalls.clear()
            val notifier = RecordingNotifier()
            val handler = PushMessageHandler(keys, { gen }, notifier, log = {}, present = presentFn)
            val json = """{"v":1,"title":"LEGACY_T","body":"LEGACY_B","presentation":{"title":{"v":1,"catalog_epoch":1,"code":"push.test.title","args":{},"fallback_en":"FALLBACK_T"},"body":{"v":1,"catalog_epoch":1,"code":"push.test.body","args":{},"fallback_en":"FALLBACK_B"}}}"""
            val env = sealTestEnvelope(key, nonce, padPlaintext(json))
            handler.onMessage(env, true)
            val post = notifier.posts.single()
            assertEquals("SENTINEL_CATALOG_push.test.title", post.title)
            assertEquals("SENTINEL_CATALOG_push.test.body", post.body)
            assertEquals(listOf("push.test.title", "push.test.body"), renderCalls.map { it.messageId })
        }

        // Case 3: Unknown code on one side posts fallback_en
        run {
            renderCalls.clear()
            val notifier = RecordingNotifier()
            val handler = PushMessageHandler(keys, { gen }, notifier, log = {}, present = presentFn)
            val json = """{"v":1,"title":"LEGACY_T","body":"LEGACY_B","presentation":{"title":{"v":1,"catalog_epoch":1,"code":"push.unknown.title","args":{},"fallback_en":"FALLBACK_T"},"body":{"v":1,"catalog_epoch":1,"code":"push.test.body","args":{},"fallback_en":"FALLBACK_B"}}}"""
            val env = sealTestEnvelope(key, nonce, padPlaintext(json))
            handler.onMessage(env, true)
            val post = notifier.posts.single()
            assertEquals("FALLBACK_T", post.title)
            assertEquals("SENTINEL_CATALOG_push.test.body", post.body)
            assertFalse(renderCalls.any { it.messageId.contains("title") })
            assertEquals(listOf("push.test.body"), renderCalls.map { it.messageId })
        }

        // Required Case 1: Otherwise-valid title with fallback_en omitted, valid body with string fallback_en:
        // posted title is legacy title, posted body is catalog sentinel, render not called for title.
        run {
            renderCalls.clear()
            val notifier = RecordingNotifier()
            val handler = PushMessageHandler(keys, { gen }, notifier, log = {}, present = presentFn)
            val json = """{"v":1,"title":"LEGACY_T","body":"LEGACY_B","presentation":{"title":{"v":1,"catalog_epoch":1,"code":"push.test.title","args":{}},"body":{"v":1,"catalog_epoch":1,"code":"push.test.body","args":{},"fallback_en":"FALLBACK_B"}}}"""
            val env = sealTestEnvelope(key, nonce, padPlaintext(json))
            handler.onMessage(env, true)
            val post = notifier.posts.single()
            assertEquals("LEGACY_T", post.title)
            assertEquals("SENTINEL_CATALOG_push.test.body", post.body)
            assertFalse(renderCalls.any { it.messageId == "push.test.title" })
            assertEquals(listOf("push.test.body"), renderCalls.map { it.messageId })
        }

        // Required Case 2: Same shape with "fallback_en": true on title:
        // title stays legacy, body still renders, render not called for title.
        run {
            renderCalls.clear()
            val notifier = RecordingNotifier()
            val handler = PushMessageHandler(keys, { gen }, notifier, log = {}, present = presentFn)
            val json = """{"v":1,"title":"LEGACY_T","body":"LEGACY_B","presentation":{"title":{"v":1,"catalog_epoch":1,"code":"push.test.title","args":{},"fallback_en":true},"body":{"v":1,"catalog_epoch":1,"code":"push.test.body","args":{},"fallback_en":"FALLBACK_B"}}}"""
            val env = sealTestEnvelope(key, nonce, padPlaintext(json))
            handler.onMessage(env, true)
            val post = notifier.posts.single()
            assertEquals("LEGACY_T", post.title)
            assertEquals("SENTINEL_CATALOG_push.test.body", post.body)
            assertFalse(renderCalls.any { it.messageId == "push.test.title" })
            assertEquals(listOf("push.test.body"), renderCalls.map { it.messageId })
        }

        // Required Case 3: Title has string fallback_en but no args key (do not send arguments):
        // title is fallback_en, render not called for title, body still renders.
        run {
            renderCalls.clear()
            val notifier = RecordingNotifier()
            val handler = PushMessageHandler(keys, { gen }, notifier, log = {}, present = presentFn)
            val json = """{"v":1,"title":"LEGACY_T","body":"LEGACY_B","presentation":{"title":{"v":1,"catalog_epoch":1,"code":"push.test.title","fallback_en":"FALLBACK_T"},"body":{"v":1,"catalog_epoch":1,"code":"push.test.body","args":{},"fallback_en":"FALLBACK_B"}}}"""
            val env = sealTestEnvelope(key, nonce, padPlaintext(json))
            handler.onMessage(env, true)
            val post = notifier.posts.single()
            assertEquals("FALLBACK_T", post.title)
            assertEquals("SENTINEL_CATALOG_push.test.body", post.body)
            assertFalse(renderCalls.any { it.messageId == "push.test.title" })
            assertEquals(listOf("push.test.body"), renderCalls.map { it.messageId })
        }

        // Required Case 4: Title uses "id":"push.test.title" and "arguments":{} without code or args, with string fallback_en:
        // title is fallback_en, not catalog sentinel, render not called for title, body still renders.
        run {
            renderCalls.clear()
            val notifier = RecordingNotifier()
            val handler = PushMessageHandler(keys, { gen }, notifier, log = {}, present = presentFn)
            val json = """{"v":1,"title":"LEGACY_T","body":"LEGACY_B","presentation":{"title":{"v":1,"catalog_epoch":1,"id":"push.test.title","arguments":{},"fallback_en":"FALLBACK_T"},"body":{"v":1,"catalog_epoch":1,"code":"push.test.body","args":{},"fallback_en":"FALLBACK_B"}}}"""
            val env = sealTestEnvelope(key, nonce, padPlaintext(json))
            handler.onMessage(env, true)
            val post = notifier.posts.single()
            assertEquals("FALLBACK_T", post.title)
            assertEquals("SENTINEL_CATALOG_push.test.body", post.body)
            assertFalse(renderCalls.any { it.messageId == "push.test.title" })
            assertEquals(listOf("push.test.body"), renderCalls.map { it.messageId })
        }

        // Case 5: presentation set to JSON string or array posts legacy title and body
        run {
            renderCalls.clear()
            val notifier = RecordingNotifier()
            val handler = PushMessageHandler(keys, { gen }, notifier, log = {}, present = presentFn)
            val json = """{"v":1,"title":"LEGACY_T","body":"LEGACY_B","presentation":["array_not_object"]}"""
            val env = sealTestEnvelope(key, nonce, padPlaintext(json))
            handler.onMessage(env, true)
            val post = notifier.posts.single()
            assertEquals("LEGACY_T", post.title)
            assertEquals("LEGACY_B", post.body)
            assertTrue(renderCalls.isEmpty())
        }

        // Case 6: Bad version, auth failure, bad padding -> FALLBACK_TITLE, FALLBACK_BODY, null open
        run {
            val notifier = RecordingNotifier()
            val handler = PushMessageHandler(keys, { gen }, notifier, log = {}, present = presentFn)

            // Bad version
            val json = """{"v":1,"title":"LEGACY_T","body":"LEGACY_B"}"""
            val badVerEnv = sealTestEnvelope(key, nonce, padPlaintext(json), version = 0x02.toByte())
            handler.onMessage(badVerEnv, true)
            assertEquals(PushMessageHandler.FALLBACK_TITLE, notifier.posts[0].title)
            assertEquals(PushMessageHandler.FALLBACK_BODY, notifier.posts[0].body)
            assertNull(notifier.posts[0].open)

            // Auth failure
            val goodEnv = sealTestEnvelope(key, nonce, padPlaintext(json))
            goodEnv[20] = (goodEnv[20].toInt() xor 0xFF).toByte()
            handler.onMessage(goodEnv, true)
            assertEquals(PushMessageHandler.FALLBACK_TITLE, notifier.posts[1].title)
            assertEquals(PushMessageHandler.FALLBACK_BODY, notifier.posts[1].body)
            assertNull(notifier.posts[1].open)

            // Bad padding
            val badPadPlaintext = ByteArray(1024) { 0 }
            val badPadEnv = sealTestEnvelope(key, nonce, badPadPlaintext)
            handler.onMessage(badPadEnv, true)
            assertEquals(PushMessageHandler.FALLBACK_TITLE, notifier.posts[2].title)
            assertEquals(PushMessageHandler.FALLBACK_BODY, notifier.posts[2].body)
            assertNull(notifier.posts[2].open)
        }
    }
}
