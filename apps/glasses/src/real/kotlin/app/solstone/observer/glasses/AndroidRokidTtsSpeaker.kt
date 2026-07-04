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

    override fun bind(callback: RokidTtsConnectionCallback): RokidTtsBinding? {
        val binding = AndroidRokidTtsBinding(appContext, callback)
        return if (binding.bind()) binding else null
    }

    private class AndroidRokidTtsBinding(
        private val appContext: Context,
        private val callback: RokidTtsConnectionCallback,
    ) : RokidTtsBinding {
        private val lock = Any()
        private val connected = CountDownLatch(1)
        private var serviceConnection: ServiceConnection? = null
        private var connection: RokidTtsConnection? = null
        private var unbound: Boolean = false

        fun bind(): Boolean {
            val nextConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val next = service
                        ?.takeIf { it.isBinderAlive }
                        ?.let { AndroidRokidTtsConnection(it) }
                    val shouldNotify = synchronized(lock) {
                        if (serviceConnection !== this || unbound) {
                            false
                        } else {
                            connection = next
                            next != null
                        }
                    }
                    if (shouldNotify && next != null) {
                        callback.onConnected(next)
                    }
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    val shouldNotify = synchronized(lock) {
                        if (serviceConnection !== this) {
                            false
                        } else {
                            connection = null
                            true
                        }
                    }
                    if (shouldNotify) {
                        callback.onDisconnected()
                    }
                    connected.countDown()
                }
            }

            synchronized(lock) {
                serviceConnection = nextConnection
            }
            val intent = Intent(ASSUMED_TTS_ACTION).setPackage(ASSUMED_TTS_PACKAGE)
            val bound = runCatching {
                appContext.bindService(intent, nextConnection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) {
                unbind()
            }
            return bound
        }

        override fun awaitConnected(timeoutMs: Long): RokidTtsConnection? {
            if (!connected.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return null
            }
            return synchronized(lock) { connection }
        }

        override fun unbind() {
            val staleConnection = synchronized(lock) {
                if (unbound) {
                    null
                } else {
                    unbound = true
                    connection = null
                    serviceConnection.also { serviceConnection = null }
                }
            }
            if (staleConnection != null) {
                runCatching { appContext.unbindService(staleConnection) }
            }
        }
    }

    private class AndroidRokidTtsConnection(
        private val service: IBinder,
    ) : RokidTtsConnection {
        override fun speak(phrase: String): TtsAttempt =
            runCatching {
                if (!service.isBinderAlive) return TtsAttempt.UNAVAILABLE
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
    }
}
