// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.security.auth.x500.X500Principal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CsrTest {
    @Test
    fun csrContainsExpectedSubjectPublicKeyAndVerifiableSignature() {
        val keyPair = generateP256KeyPair()
        val der = pemToDer(buildCsrPem("test-device", keyPair), "CERTIFICATE REQUEST")
        val csr = DerReader(der).readTlv()
        val csrChildren = DerReader(csr.content).readAll()
        assertEquals(3, csrChildren.size)

        val cri = csrChildren[0]
        val criChildren = DerReader(cri.content).readAll()
        assertEquals(4, criChildren.size)
        assertEquals(0x02, criChildren[0].tag)
        assertContentEquals(byteArrayOf(0x00), criChildren[0].content)
        assertEquals("CN=test-device", X500Principal(criChildren[1].fullBytes).name)

        val spki = criChildren[2]
        val spkiChildren = DerReader(spki.content).readAll()
        val algorithmChildren = DerReader(spkiChildren[0].content).readAll()
        assertEquals("1.2.840.10045.2.1", decodeOid(algorithmChildren[0].content))
        assertEquals("1.2.840.10045.3.1.7", decodeOid(algorithmChildren[1].content))

        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki.fullBytes))
        val signatureBitString = csrChildren[2]
        assertEquals(0x03, signatureBitString.tag)
        assertEquals(0, signatureBitString.content[0].toInt())
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initVerify(publicKey)
        signature.update(cri.fullBytes)
        assertTrue(signature.verify(signatureBitString.content.copyOfRange(1, signatureBitString.content.size)))
    }
}

private data class Tlv(val tag: Int, val fullBytes: ByteArray, val content: ByteArray)

private class DerReader(private val bytes: ByteArray) {
    private var index = 0

    fun readAll(): List<Tlv> {
        val out = ArrayList<Tlv>()
        while (index < bytes.size) {
            out += readTlv()
        }
        return out
    }

    fun readTlv(): Tlv {
        val start = index
        val tag = bytes[index++].toInt() and 0xff
        var length = bytes[index++].toInt() and 0xff
        if ((length and 0x80) != 0) {
            val count = length and 0x7f
            length = 0
            repeat(count) {
                length = (length shl 8) or (bytes[index++].toInt() and 0xff)
            }
        }
        val contentStart = index
        index += length
        return Tlv(tag, bytes.copyOfRange(start, index), bytes.copyOfRange(contentStart, index))
    }
}

private fun decodeOid(content: ByteArray): String {
    val parts = ArrayList<Long>()
    parts += (content[0].toInt() and 0xff) / 40L
    parts += (content[0].toInt() and 0xff) % 40L
    var value = 0L
    for (index in 1 until content.size) {
        val byte = content[index].toInt() and 0xff
        value = (value shl 7) or (byte and 0x7f).toLong()
        if ((byte and 0x80) == 0) {
            parts += value
            value = 0L
        }
    }
    return parts.joinToString(".")
}
