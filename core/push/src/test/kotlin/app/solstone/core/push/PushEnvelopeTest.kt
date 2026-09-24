// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.push

import app.solstone.core.pl.parseJson
import java.io.File
import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PushEnvelopeTest {

    private fun findVectorsFile(): File {
        var current: File? = File(System.getProperty("user.dir"))
        while (current != null) {
            val candidate = File(current, "docs/design/push-envelope-vectors.json")
            if (candidate.exists()) return candidate
            current = current.parentFile
        }
        error("docs/design/push-envelope-vectors.json not found by walking up from ${System.getProperty("user.dir")}")
    }

    private fun decodeBase64Url(s: String): ByteArray {
        val pad = (4 - (s.length % 4)) % 4
        val padded = s + "=".repeat(pad)
        return Base64.getUrlDecoder().decode(padded)
    }

    @Test
    fun vectorCases() {
        val file = findVectorsFile()
        val json = parseJson(file.readText()) as Map<*, *>
        val cases = json["cases"] as List<*>

        val seenNames = mutableSetOf<String>()
        var caseACopy: Pair<ByteArray, ByteArray>? = null

        for (caseObj in cases) {
            val c = caseObj as Map<*, *>
            val name = c["name"] as String
            val keyStr = c["key"] as String
            val key = decodeBase64Url(keyStr)
            val envelopeStr = c["envelope"] as String?
            val expect = c["expect"] as String
            val plaintextJson = c["plaintext_json"] as String?

            seenNames.add(name)

            when (expect) {
                "ok" -> {
                    requireNotNull(envelopeStr) { "envelope must not be null for ok case $name" }
                    requireNotNull(plaintextJson) { "plaintext_json must not be null for ok case $name" }
                    val envelope = decodeBase64Url(envelopeStr)
                    if (name == "a") {
                        caseACopy = key to envelope.copyOf()
                    }
                    val result = openEnvelope(key, envelope)
                    val opened = assertIs<OpenEnvelopeResult.Opened>(result, "case $name must open successfully")

                    val expectedJsonMap = parseJson(plaintextJson) as Map<*, *>
                    assertEquals(expectedJsonMap["title"], opened.title, "case $name title mismatch")
                    assertEquals(expectedJsonMap["body"], opened.body, "case $name body mismatch")
                    val expectedOpen = expectedJsonMap["open"] as? String
                    assertEquals(expectedOpen, opened.open, "case $name open mismatch")
                }
                "too_large" -> {
                    assertNull(envelopeStr, "case $name too_large must have null envelope")
                }
                "auth_fail" -> {
                    requireNotNull(envelopeStr) { "envelope must not be null for auth_fail case $name" }
                    val envelope = decodeBase64Url(envelopeStr)
                    val result = openEnvelope(key, envelope)
                    assertIs<OpenEnvelopeResult.AuthFailed>(result, "case $name must return AuthFailed")
                }
                "bad_version" -> {
                    requireNotNull(envelopeStr) { "envelope must not be null for bad_version case $name" }
                    val envelope = decodeBase64Url(envelopeStr)
                    val result = openEnvelope(key, envelope)
                    assertIs<OpenEnvelopeResult.BadVersion>(result, "case $name must return BadVersion")
                }
                else -> fail("Unknown vector expectation '$expect' in case $name")
            }
        }

        val requiredCases = setOf("a", "a2", "b", "c", "d", "e", "f")
        for (req in requiredCases) {
            assertTrue(seenNames.contains(req), "Missing required vector case: $req")
        }

        // Flipped byte test on case a
        val (keyA, envA) = requireNotNull(caseACopy) { "case a must have been present" }
        val flippedEnv = envA.copyOf()
        flippedEnv[13] = (flippedEnv[13].toInt() xor 0x01).toByte()
        val flippedResult = openEnvelope(keyA, flippedEnv)
        assertIs<OpenEnvelopeResult.AuthFailed>(flippedResult, "Flipped byte at index >= 13 must fail authentication")
    }

    @Test
    fun badVersionDoesNotReachCipher() {
        class ThrowingCipherSpi : CipherSpi() {
            init {
                throw IllegalStateException("Cipher SPI should never be instantiated on BadVersion")
            }
            override fun engineSetMode(mode: String?) {}
            override fun engineSetPadding(padding: String?) {}
            override fun engineGetBlockSize(): Int = 16
            override fun engineGetOutputSize(inputLen: Int): Int = inputLen
            override fun engineGetIV(): ByteArray = ByteArray(12)
            override fun engineGetParameters(): AlgorithmParameters? = null
            override fun engineInit(mode: Int, key: Key?, random: SecureRandom?) {}
            override fun engineInit(mode: Int, key: Key?, params: AlgorithmParameterSpec?, random: SecureRandom?) {}
            override fun engineInit(mode: Int, key: Key?, params: AlgorithmParameters?, random: SecureRandom?) {}
            override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray? = null
            override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int = 0
            override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray? = null
            override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int = 0
        }

        val testProvider = object : Provider("ThrowingAesProvider", 1.0, "Test throwing AES provider") {
            init {
                put("Cipher.AES/GCM/NoPadding", ThrowingCipherSpi::class.java.name)
            }
        }

        Security.insertProviderAt(testProvider, 1)
        try {
            val key = ByteArray(32) { it.toByte() }
            val envelope = ByteArray(1053) { 0x00 }
            envelope[0] = 0x02 // bad version != 0x01

            val result = openEnvelope(key, envelope)
            assertIs<OpenEnvelopeResult.BadVersion>(result)
        } finally {
            Security.removeProvider(testProvider.name)
        }
    }

    private fun sealEnvelope(
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
        val pad = ByteArray(1024 - bytes.size)
        pad[0] = 0x80.toByte()
        return bytes + pad
    }

    @Test
    fun sealerValidationCases() {
        val key = ByteArray(32) { 0x01 }
        val nonce = ByteArray(12) { 0x02 }

        // 1. Missing 0x80 (e.g. all zeros)
        val allZerosPlaintext = ByteArray(1024)
        val envZeros = sealEnvelope(key, nonce, allZerosPlaintext)
        assertIs<OpenEnvelopeResult.BadPadding>(openEnvelope(key, envZeros))

        // 2. Missing 0x80 (only ASCII content, filled with 0x00)
        val no80Plaintext = ByteArray(1024)
        val jsonBytes = """{"v":1,"title":"t","body":"b"}""".toByteArray(Charsets.UTF_8)
        System.arraycopy(jsonBytes, 0, no80Plaintext, 0, jsonBytes.size)
        val envNo80 = sealEnvelope(key, nonce, no80Plaintext)
        assertIs<OpenEnvelopeResult.BadPadding>(openEnvelope(key, envNo80))

        // 3. Non-zero byte after 0x80
        val corruptedPadPlaintext = padPlaintext("""{"v":1,"title":"t","body":"b"}""")
        corruptedPadPlaintext[1000] = 0x01.toByte()
        val envCorruptedPad = sealEnvelope(key, nonce, corruptedPadPlaintext)
        assertIs<OpenEnvelopeResult.BadPadding>(openEnvelope(key, envCorruptedPad))

        // 4. "v":2
        val v2Plaintext = padPlaintext("""{"v":2,"title":"t","body":"b"}""")
        val envV2 = sealEnvelope(key, nonce, v2Plaintext)
        assertIs<OpenEnvelopeResult.BadPlaintext>(openEnvelope(key, envV2))

        // 5. Missing title
        val missingTitlePlaintext = padPlaintext("""{"v":1,"body":"b"}""")
        val envMissingTitle = sealEnvelope(key, nonce, missingTitlePlaintext)
        assertIs<OpenEnvelopeResult.BadPlaintext>(openEnvelope(key, envMissingTitle))

        // 6. Numeric title
        val numericTitlePlaintext = padPlaintext("""{"v":1,"title":123,"body":"b"}""")
        val envNumericTitle = sealEnvelope(key, nonce, numericTitlePlaintext)
        assertIs<OpenEnvelopeResult.BadPlaintext>(openEnvelope(key, envNumericTitle))

        // 7. Extra keys ignored and open succeeded
        val extraKeysPlaintext = padPlaintext("""{"v":1,"title":"t","body":"b","extra":"value","open":"/app/home"}""")
        val envExtra = sealEnvelope(key, nonce, extraKeysPlaintext)
        val resExtra = openEnvelope(key, envExtra)
        val openedExtra = assertIs<OpenEnvelopeResult.Opened>(resExtra)
        assertEquals("t", openedExtra.title)
        assertEquals("b", openedExtra.body)
        assertEquals("/app/home", openedExtra.open)

        // 8. Key / Length errors
        assertIs<OpenEnvelopeResult.BadKey>(openEnvelope(ByteArray(31), ByteArray(1053)))
        assertIs<OpenEnvelopeResult.BadLength>(openEnvelope(ByteArray(32), ByteArray(1052)))
        assertIs<OpenEnvelopeResult.BadLength>(openEnvelope(ByteArray(32), ByteArray(1054)))
    }
}
