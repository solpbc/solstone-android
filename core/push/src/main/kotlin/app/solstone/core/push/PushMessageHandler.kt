// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.push

import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PushKeyAccess
import java.security.SecureRandom

fun interface PushNotifier {
    fun post(id: Int, title: String, body: String, open: String?)
}

private val defaultSecureRandom = SecureRandom()

internal fun defaultNotificationIdDraw(): Int = defaultSecureRandom.nextInt()

internal fun nextNotificationId(draw: () -> Int = ::defaultNotificationIdDraw): Int {
    while (true) {
        val candidate = draw()
        if (candidate !in 100..199 && candidate !in 201..203) {
            return candidate
        }
    }
}

class PushMessageHandler(
    private val pushKeys: PushKeyAccess,
    private val pairingNow: () -> PairingGeneration?,
    private val notifier: PushNotifier,
    private val log: (String) -> Unit,
    private val drawId: () -> Int = ::defaultNotificationIdDraw,
) {
    companion object {
        const val FALLBACK_TITLE = "solstone"
        const val FALLBACK_BODY = "you have a new notification."
    }

    fun onMessage(content: ByteArray, decrypted: Boolean) {
        val id = nextNotificationId(drawId)
        if (!decrypted) {
            log("kind=push reason=not_decrypted")
            notifier.post(id, FALLBACK_TITLE, FALLBACK_BODY, null)
            return
        }

        val generation = pairingNow()
        if (generation == null) {
            log("kind=push reason=no_key")
            notifier.post(id, FALLBACK_TITLE, FALLBACK_BODY, null)
            return
        }

        val keyBytes = try {
            pushKeys.readPushKey(generation)
        } catch (_: Throwable) {
            log("kind=push reason=internal")
            notifier.post(id, FALLBACK_TITLE, FALLBACK_BODY, null)
            return
        }

        if (keyBytes == null) {
            log("kind=push reason=no_key")
            notifier.post(id, FALLBACK_TITLE, FALLBACK_BODY, null)
            return
        }

        val envelopeResult = try {
            openEnvelope(keyBytes, content)
        } catch (_: Throwable) {
            log("kind=push reason=internal")
            notifier.post(id, FALLBACK_TITLE, FALLBACK_BODY, null)
            return
        }

        when (envelopeResult) {
            is OpenEnvelopeResult.Opened -> {
                notifier.post(id, envelopeResult.title, envelopeResult.body, envelopeResult.open)
            }
            OpenEnvelopeResult.BadKey,
            OpenEnvelopeResult.BadLength,
            OpenEnvelopeResult.BadVersion,
            OpenEnvelopeResult.BadPadding -> {
                log("kind=push reason=bad_envelope")
                notifier.post(id, FALLBACK_TITLE, FALLBACK_BODY, null)
            }
            OpenEnvelopeResult.AuthFailed -> {
                log("kind=push reason=auth_failed")
                notifier.post(id, FALLBACK_TITLE, FALLBACK_BODY, null)
            }
            OpenEnvelopeResult.BadPlaintext -> {
                log("kind=push reason=bad_plaintext")
                notifier.post(id, FALLBACK_TITLE, FALLBACK_BODY, null)
            }
        }
    }
}
