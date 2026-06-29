// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AndroidRokidTtsSpeaker(context: Context) : RokidTtsSpeaker {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var binder: IBinder? = null
    private var connection: ServiceConnection? = null
    private var disconnected: Boolean = false

    override fun speak(phrase: String): TtsAttempt =
        runCatching {
            val service = boundService() ?: return TtsAttempt.UNAVAILABLE
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ASSUMED_TTS_DESCRIPTOR)
                data.writeString(phrase)
                if (!service.transact(ASSUMED_TRANSACTION_SPEAK, data, reply, 0)) {
                    return TtsAttempt.UNAVAILABLE
                }
                reply.readException()
                TtsAttempt.SPOKEN
            } finally {
                reply.recycle()
                data.recycle()
            }
        }.getOrDefault(TtsAttempt.UNAVAILABLE)

    private fun boundService(): IBinder? {
        synchronized(lock) {
            if (disconnected) return null
            binder?.takeIf { it.isBinderAlive }?.let { return it }
        }

        val connected = CountDownLatch(1)
        val nextConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                synchronized(lock) {
                    if (connection !== this) {
                        connected.countDown()
                        return
                    }
                    binder = service
                    disconnected = false
                }
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                synchronized(lock) {
                    binder = null
                    connection = null
                    disconnected = true
                }
            }
        }

        val intent = Intent(ASSUMED_TTS_ACTION).setPackage(ASSUMED_TTS_PACKAGE)
        synchronized(lock) {
            connection = nextConnection
        }
        val bound = runCatching { appContext.bindService(intent, nextConnection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!bound) {
            unbind(nextConnection)
            return null
        }
        if (!connected.await(TTS_BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            unbind(nextConnection)
            return null
        }
        return synchronized(lock) { binder?.takeIf { it.isBinderAlive } }
    }

    private fun unbind(staleConnection: ServiceConnection) {
        synchronized(lock) {
            if (connection !== staleConnection) return
            binder = null
            connection = null
        }
        runCatching { appContext.unbindService(staleConnection) }
    }

    private companion object {
        /*
         * ASSUMED/UNVERIFIED Rokid local TTS binder surface. docs/device-matrix.md confirms
         * RV203 hardware only; these package/action/descriptor/transaction details must be
         * confirmed on RV203 hardware. The speak transaction shape is a best-effort guess:
         * interface token + phrase String, synchronous reply/readException.
         */
        const val ASSUMED_TTS_PACKAGE = "com.rokid.tts"
        const val ASSUMED_TTS_ACTION = "com.rokid.tts.intent.action.BIND_TTS_SERVICE"
        const val ASSUMED_TTS_DESCRIPTOR = "com.rokid.tts.IRokidTtsService"
        const val ASSUMED_TRANSACTION_SPEAK = IBinder.FIRST_CALL_TRANSACTION
        const val TTS_BIND_TIMEOUT_MS = 750L
    }
}
