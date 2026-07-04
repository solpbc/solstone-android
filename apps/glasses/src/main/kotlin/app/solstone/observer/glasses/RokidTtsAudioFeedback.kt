// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.diagnostics.StatusCue
import java.util.concurrent.Executors

enum class TtsAttempt { SPOKEN, UNAVAILABLE }

interface RokidTtsConnection {
    fun speak(phrase: String): TtsAttempt
}

interface RokidTtsBinding {
    fun awaitConnected(timeoutMs: Long): RokidTtsConnection?
    fun unbind()
}

interface RokidTtsConnectionCallback {
    fun onConnected(connection: RokidTtsConnection)
    fun onDisconnected()
}

fun interface RokidTtsSpeaker {
    fun bind(callback: RokidTtsConnectionCallback): RokidTtsBinding?
}

class RokidTtsAudioFeedback(
    private val speaker: RokidTtsSpeaker,
    private val fallback: AudioFeedback,
    private val phraseFor: (StatusCue) -> String = ::phraseFor,
    private val worker: ((() -> Unit) -> Unit) = { task -> defaultWorker.execute(task) },
    private val diag: (String) -> Unit = {},
) : AudioFeedback {
    private val lock = Any()
    private var connection: RokidTtsConnection? = null
    private var binding: RokidTtsBinding? = null
    private var lastDegradeReason: String? = null

    val degraded: Boolean
        get() = synchronized(lock) { lastDegradeReason != null }

    private val callback = object : RokidTtsConnectionCallback {
        override fun onConnected(connection: RokidTtsConnection) {
            synchronized(lock) {
                this@RokidTtsAudioFeedback.connection = connection
            }
        }

        override fun onDisconnected() {
            val stale = synchronized(lock) {
                val current = binding
                connection = null
                binding = null
                current
            }
            stale?.unbind()
        }
    }

    init {
        worker { ensureConnection() }
    }

    override fun play(cue: StatusCue) {
        worker {
            playOnWorker(cue)
        }
    }

    private fun playOnWorker(cue: StatusCue) {
        val result = try {
            val current = ensureConnection() ?: return degrade(cue, DEGRADE_UNAVAILABLE)
            current.speak(phraseFor(cue))
        } catch (t: Throwable) {
            return degrade(cue, t.javaClass.simpleName)
        }
        if (result == TtsAttempt.SPOKEN) {
            clearDegrade()
        } else {
            degrade(cue, DEGRADE_UNAVAILABLE)
        }
    }

    private fun ensureConnection(): RokidTtsConnection? {
        synchronized(lock) {
            connection?.let { return it }
        }
        val currentBinding = synchronized(lock) {
            binding ?: speaker.bind(callback)
                ?.also {
                    binding = it
                }
        } ?: return null
        val connected = try {
            currentBinding.awaitConnected(TTS_BIND_TIMEOUT_MS)
        } catch (t: Throwable) {
            synchronized(lock) {
                if (binding === currentBinding) {
                    binding = null
                    connection = null
                }
            }
            currentBinding.unbind()
            throw t
        }
        return synchronized(lock) {
            if (connected != null) {
                connection = connected
                connected
            } else {
                if (binding === currentBinding) {
                    binding = null
                    connection = null
                }
                currentBinding.unbind()
                null
            }
        }
    }

    private fun degrade(cue: StatusCue, reason: String) {
        val shouldDiag = synchronized(lock) {
            val changed = lastDegradeReason != reason
            lastDegradeReason = reason
            changed
        }
        if (shouldDiag) {
            diag("tts-degraded reason=$reason")
        }
        fallback.play(cue)
    }

    private fun clearDegrade() {
        synchronized(lock) {
            lastDegradeReason = null
        }
    }

    private companion object {
        const val TTS_BIND_TIMEOUT_MS = 750L
        const val DEGRADE_UNAVAILABLE = "UNAVAILABLE"
        val defaultWorker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "rokid-tts").also { it.isDaemon = true }
        }
    }
}
