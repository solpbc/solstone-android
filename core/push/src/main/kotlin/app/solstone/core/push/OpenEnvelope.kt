// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.push

import app.solstone.core.pl.parseJson
import java.math.BigDecimal
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

sealed interface OpenEnvelopeResult {
    data class Opened(val title: String, val body: String, val open: String?) : OpenEnvelopeResult
    data object BadKey : OpenEnvelopeResult
    data object BadLength : OpenEnvelopeResult
    data object BadVersion : OpenEnvelopeResult
    data object AuthFailed : OpenEnvelopeResult
    data object BadPadding : OpenEnvelopeResult
    data object BadPlaintext : OpenEnvelopeResult
}

fun openEnvelope(key: ByteArray, envelope: ByteArray): OpenEnvelopeResult {
    if (key.size != 32) return OpenEnvelopeResult.BadKey
    if (envelope.size != 1053) return OpenEnvelopeResult.BadLength
    if (envelope[0] != 0x01.toByte()) return OpenEnvelopeResult.BadVersion

    val nonce = envelope.copyOfRange(1, 13)
    val ciphertextAndTag = envelope.copyOfRange(13, envelope.size)

    val plaintext = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(byteArrayOf(0x01.toByte()))
        cipher.doFinal(ciphertextAndTag)
    } catch (_: Exception) {
        return OpenEnvelopeResult.AuthFailed
    }

    if (plaintext.size != 1024) return OpenEnvelopeResult.BadPadding

    var padIndex = -1
    for (i in plaintext.size - 1 downTo 0) {
        val b = plaintext[i]
        if (b == 0x00.toByte()) {
            continue
        } else if ((b.toInt() and 0xFF) == 0x80) {
            padIndex = i
            break
        } else {
            return OpenEnvelopeResult.BadPadding
        }
    }
    if (padIndex < 0) return OpenEnvelopeResult.BadPadding

    val unpadded = plaintext.copyOfRange(0, padIndex)
    val jsonText = try {
        unpadded.decodeToString()
    } catch (_: Exception) {
        return OpenEnvelopeResult.BadPlaintext
    }

    val parsed = try {
        parseJson(jsonText)
    } catch (_: Exception) {
        return OpenEnvelopeResult.BadPlaintext
    }

    val root = parsed as? Map<*, *> ?: return OpenEnvelopeResult.BadPlaintext
    val v = root["v"] as? BigDecimal ?: return OpenEnvelopeResult.BadPlaintext
    if (v.compareTo(BigDecimal.ONE) != 0) return OpenEnvelopeResult.BadPlaintext

    val title = root["title"] as? String ?: return OpenEnvelopeResult.BadPlaintext
    val body = root["body"] as? String ?: return OpenEnvelopeResult.BadPlaintext
    val open = root["open"] as? String

    return OpenEnvelopeResult.Opened(title = title, body = body, open = open)
}
