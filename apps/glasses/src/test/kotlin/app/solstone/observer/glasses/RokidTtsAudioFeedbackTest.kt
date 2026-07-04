// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.diagnostics.StatusCue
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RokidTtsAudioFeedbackTest {
    @Test
    fun spokenDoesNotPlayFallbackOrDegrade() {
        val worker = ManualWorker()
        val fallback = RecordingAudioFeedback()
        val connection = FakeConnection(TtsAttempt.SPOKEN)
        val speaker = FakeSpeaker { connection }
        val audio = RokidTtsAudioFeedback(speaker, fallback, worker = worker::execute)

        audio.play(StatusCue.OBSERVING)
        worker.runAll()

        assertEquals(emptyList(), fallback.played)
        assertFalse(audio.degraded)
        assertEquals(1, connection.calls)
    }

    @Test
    fun unavailableFallsBackWithSameCueAndDegrades() {
        val worker = ManualWorker()
        val fallback = RecordingAudioFeedback()
        val diag = mutableListOf<String>()
        val connection = FakeConnection(TtsAttempt.UNAVAILABLE, TtsAttempt.SPOKEN)
        val speaker = FakeSpeaker { connection }
        val audio = RokidTtsAudioFeedback(
            speaker = speaker,
            fallback = fallback,
            worker = worker::execute,
            diag = diag::add,
        )

        audio.play(StatusCue.NOT_PAIRED)
        worker.runAll()

        assertEquals(listOf(StatusCue.NOT_PAIRED), fallback.played)
        assertTrue(audio.degraded)
        assertEquals(listOf("tts-degraded reason=UNAVAILABLE"), diag)
        assertEquals(1, connection.calls)

        audio.play(StatusCue.OBSERVER_PAUSED)
        worker.runAll()

        assertEquals(2, connection.calls)
        assertEquals(listOf(StatusCue.NOT_PAIRED), fallback.played)
        assertFalse(audio.degraded)
    }

    @Test
    fun thrownSpeakerFailureFallsBack() {
        val worker = ManualWorker()
        val fallback = RecordingAudioFeedback()
        val diag = mutableListOf<String>()
        val connection = FakeConnection(error = IllegalStateException("missing service"))
        val speaker = FakeSpeaker { connection }
        val audio = RokidTtsAudioFeedback(
            speaker = speaker,
            fallback = fallback,
            worker = worker::execute,
            diag = diag::add,
        )

        audio.play(StatusCue.NEEDS_ATTENTION)
        worker.runAll()

        assertEquals(listOf(StatusCue.NEEDS_ATTENTION), fallback.played)
        assertTrue(audio.degraded)
        assertEquals(listOf("tts-degraded reason=IllegalStateException"), diag)
        assertEquals(1, connection.calls)

        audio.play(StatusCue.OBSERVER_PAUSED)
        worker.runAll()

        assertEquals(2, connection.calls)
        assertEquals(
            listOf(StatusCue.NEEDS_ATTENTION, StatusCue.OBSERVER_PAUSED),
            fallback.played,
        )
        assertEquals(listOf("tts-degraded reason=IllegalStateException"), diag)
    }

    @Test
    fun spokenThenUnavailableFallsBackThenRecovers() {
        val worker = ManualWorker()
        val fallback = RecordingAudioFeedback()
        val diag = mutableListOf<String>()
        val connection = FakeConnection(
            TtsAttempt.SPOKEN,
            TtsAttempt.UNAVAILABLE,
            TtsAttempt.SPOKEN,
            TtsAttempt.UNAVAILABLE,
        )
        val speaker = FakeSpeaker { connection }
        val audio = RokidTtsAudioFeedback(
            speaker = speaker,
            fallback = fallback,
            worker = worker::execute,
            diag = diag::add,
        )

        audio.play(StatusCue.OBSERVING)
        audio.play(StatusCue.SYNC_FAILED)
        audio.play(StatusCue.PAIRED)
        audio.play(StatusCue.BATTERY_LOW)
        worker.runAll()

        assertEquals(4, connection.calls)
        assertEquals(listOf(StatusCue.SYNC_FAILED, StatusCue.BATTERY_LOW), fallback.played)
        assertEquals(
            listOf("tts-degraded reason=UNAVAILABLE", "tts-degraded reason=UNAVAILABLE"),
            diag,
        )
        assertTrue(audio.degraded)
    }

    @Test
    fun fallbackNeverSuppressesLaterSpeakerRetry() {
        val worker = ManualWorker()
        val fallback = RecordingAudioFeedback()
        val connection = FakeConnection(
            TtsAttempt.UNAVAILABLE,
            TtsAttempt.SPOKEN,
            TtsAttempt.UNAVAILABLE,
        )
        val speaker = FakeSpeaker { connection }
        val audio = RokidTtsAudioFeedback(speaker, fallback, worker = worker::execute)

        audio.play(StatusCue.OBSERVING)
        audio.play(StatusCue.OBSERVER_PAUSED)
        audio.play(StatusCue.NOT_PAIRED)
        worker.runAll()

        assertEquals(3, connection.calls)
        assertEquals(
            listOf(StatusCue.OBSERVING, StatusCue.NOT_PAIRED),
            fallback.played,
        )
        assertTrue(audio.degraded)
    }

    @Test
    fun mainThreadCueReturnsWithoutBlocking() {
        val worker = ManualWorker()
        val fallback = RecordingAudioFeedback()
        val connection = FakeConnection(TtsAttempt.SPOKEN)
        val speaker = FakeSpeaker(autoConnect = false) { connection }
        val audio = RokidTtsAudioFeedback(speaker, fallback, worker = worker::execute)

        audio.play(StatusCue.OBSERVING)

        assertEquals(2, worker.pendingCount)
        assertEquals(0, connection.calls)

        val workerBlockedOnBind = CountDownLatch(1)
        val workerFinished = AtomicBoolean(false)
        val bindThread = thread(start = true) {
            workerBlockedOnBind.countDown()
            worker.runOne()
            workerFinished.set(true)
        }

        assertTrue(workerBlockedOnBind.await(1, TimeUnit.SECONDS))
        waitUntil { speaker.bindings.isNotEmpty() }
        assertFalse(workerFinished.get())
        speaker.bindings.single().connect()
        bindThread.join(1_000)
        assertTrue(workerFinished.get())

        worker.runAll()

        assertEquals(1, connection.calls)
        assertEquals(emptyList(), fallback.played)
        assertFalse(audio.degraded)
    }

    @Test
    fun rebindAfterDisconnectSucceeds() {
        val worker = ManualWorker()
        val fallback = RecordingAudioFeedback()
        val connections = mutableListOf<FakeConnection>()
        val speaker = FakeSpeaker {
            FakeConnection(TtsAttempt.SPOKEN).also { connections += it }
        }
        val audio = RokidTtsAudioFeedback(speaker, fallback, worker = worker::execute)
        worker.runAll()

        audio.play(StatusCue.OBSERVING)
        worker.runAll()

        assertEquals(1, speaker.bindings.size)
        assertEquals(1, connections.single().calls)

        speaker.bindings.single().disconnect()

        assertEquals(1, speaker.bindings.single().unbindCalls)

        audio.play(StatusCue.PAIRED)
        worker.runAll()

        assertEquals(2, speaker.bindings.size)
        assertEquals(1, connections[1].calls)
        assertEquals(emptyList(), fallback.played)
        assertFalse(audio.degraded)
    }

    @Test
    fun degradeDiagEmittedExactlyOncePerTransition() {
        val worker = ManualWorker()
        val fallback = RecordingAudioFeedback()
        val diag = mutableListOf<String>()
        val connection = FakeConnection(
            TtsAttempt.UNAVAILABLE,
            TtsAttempt.UNAVAILABLE,
            TtsAttempt.SPOKEN,
            TtsAttempt.UNAVAILABLE,
        )
        val speaker = FakeSpeaker { connection }
        val audio = RokidTtsAudioFeedback(
            speaker = speaker,
            fallback = fallback,
            worker = worker::execute,
            diag = diag::add,
        )

        audio.play(StatusCue.OBSERVING)
        audio.play(StatusCue.NOT_PAIRED)
        audio.play(StatusCue.PAIRED)
        audio.play(StatusCue.SYNC_FAILED)
        worker.runAll()

        assertEquals(4, connection.calls)
        assertEquals(
            listOf(StatusCue.OBSERVING, StatusCue.NOT_PAIRED, StatusCue.SYNC_FAILED),
            fallback.played,
        )
        assertEquals(
            listOf("tts-degraded reason=UNAVAILABLE", "tts-degraded reason=UNAVAILABLE"),
            diag,
        )
    }

    private class ManualWorker {
        private val tasks = ArrayDeque<() -> Unit>()

        val pendingCount: Int
            get() = tasks.size

        fun execute(task: () -> Unit) {
            tasks.add(task)
        }

        fun runOne() {
            tasks.removeFirst().invoke()
        }

        fun runAll() {
            while (tasks.isNotEmpty()) {
                runOne()
            }
        }
    }

    private class RecordingAudioFeedback : AudioFeedback {
        val played = mutableListOf<StatusCue>()

        override fun play(cue: StatusCue) {
            played.add(cue)
        }
    }

    private class FakeSpeaker(
        private val autoConnect: Boolean = true,
        private val connectionFactory: () -> FakeConnection,
    ) : RokidTtsSpeaker {
        val bindings = mutableListOf<FakeBinding>()

        override fun bind(callback: RokidTtsConnectionCallback): RokidTtsBinding {
            val binding = FakeBinding(callback, connectionFactory())
            bindings += binding
            if (autoConnect) {
                binding.connect()
            }
            return binding
        }
    }

    private class FakeBinding(
        private val callback: RokidTtsConnectionCallback,
        private val connection: FakeConnection,
    ) : RokidTtsBinding {
        private val connected = CountDownLatch(1)
        var unbindCalls = 0
            private set

        fun connect() {
            callback.onConnected(connection)
            connected.countDown()
        }

        fun disconnect() {
            callback.onDisconnected()
        }

        override fun awaitConnected(timeoutMs: Long): RokidTtsConnection? =
            if (connected.await(timeoutMs, TimeUnit.MILLISECONDS)) connection else null

        override fun unbind() {
            unbindCalls += 1
        }
    }

    private class FakeConnection(
        private vararg val attempts: TtsAttempt,
        private val error: RuntimeException? = null,
    ) : RokidTtsConnection {
        var calls = 0
            private set

        override fun speak(phrase: String): TtsAttempt {
            calls += 1
            error?.let { throw it }
            return attempts.getOrElse(calls - 1) { attempts.last() }
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (!predicate() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(predicate())
    }
}
