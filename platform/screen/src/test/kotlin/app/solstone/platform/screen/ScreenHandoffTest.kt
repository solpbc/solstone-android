// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import android.content.Intent
import android.util.DisplayMetrics
import app.solstone.core.model.ReasonCode
import app.solstone.core.observer.CapturePipeline
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.segment.Segmenter
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.SourceEmission
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.SealResult
import app.solstone.core.spool.SpoolWriter
import app.solstone.core.spool.GapNoticeStore
import app.solstone.platform.audio.AudioCommitStatus
import app.solstone.platform.audio.AudioSpoolLookup
import app.solstone.platform.fgs.ScreenConsentToken
import app.solstone.platform.power.StorageStatus
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScreenHandoffTest {
    private enum class Stage { NONE, SPOOL_BLOCK, SPOOL_FAIL, ROOM_BLOCK, ROOM_FAIL }

    private class Bench(val stage: Stage = Stage.NONE, usePipeline: Boolean = false) : AutoCloseable {
        val root = Files.createTempDirectory("screen-handoff").toFile()
        val source = File(root, "source").apply { mkdirs() }
        val clock = AtomicLong(1_800_000_000_000L)
        val free = AtomicLong(100_000L)
        val wake = Semaphore(0)
        var blockControl = false
        val controlEntered = CountDownLatch(1)
        val releaseControl = CountDownLatch(1)
        val encoders = CopyOnWriteArrayList<Encoder>()
        val attached = LinkedBlockingQueue<Encoder>()
        val submitted = LinkedBlockingQueue<Int>()
        val released = LinkedBlockingQueue<Int>()
        val terminals = LinkedBlockingQueue<String>()
        val remuxEntered = LinkedBlockingQueue<Int>()
        val roomEntered = LinkedBlockingQueue<Int>()
        val remuxGates = ConcurrentHashMap<Int, CountDownLatch>()
        val remuxFailures = ConcurrentHashMap.newKeySet<Int>()
        val roomGates = ConcurrentHashMap<Int, CountDownLatch>()
        val spoolGate = CountDownLatch(1)
        val stageEntered = CountDownLatch(1)
        val stageFailed = CountDownLatch(1)
        val failedFeeds = Semaphore(0)
        val persisted = CopyOnWriteArrayList<SealedSegment>()
        val unresolved = AtomicBoolean(false)
        val unresolvedMarked = CountDownLatch(1)
        var projections = 0
        var displays = 0
        lateinit var callback: ScreenProjectionCallback
        lateinit var engine: ScreenContinuousSourceEngine
        private lateinit var liveSink: EmissionSink
        val holder = ScreenSessionHolder(object : ScreenProjectionManager {
            override val getMediaProjectionCount: Int get() = projections
            override val createVirtualDisplayCount: Int get() = displays
            override fun acquireProjection(token: ScreenConsentToken): ScreenProjection {
                projections++
                return object : ScreenProjection {
                    override fun registerCallback(value: ScreenProjectionCallback) { callback = value }
                    override fun createVirtualDisplay(name: String, width: Int, height: Int, dpi: Int,
                        surface: Any?, flags: Int): ScreenVirtualDisplay {
                        displays++
                        return object : ScreenVirtualDisplay {
                            override fun setSurface(value: Any?) { (value as? Encoder)?.frameAfterAttachment() }
                            override fun resize(width: Int, height: Int, dpi: Int) {}
                            override fun release() {}
                        }
                    }
                    override fun stop() {}
                }
            }
        }, { DisplayMetrics().apply { widthPixels = 720; heightPixels = 1280; densityDpi = 320 } },
            { engine.stop() })
        val gaps = GapNoticeStore(File(root, "gap").toPath(), listener = { _, _, _, _ ->
            unresolved.set(true)
            unresolvedMarked.countDown()
        })
        val remuxer = ScreenRemuxer { _, raw, _, _, output ->
            val number = raw.readBytes().first().toInt()
            remuxEntered.offer(number)
            remuxGates[number]?.awaitUninterrupted()
            check(number !in remuxFailures) { "remux failed" }
            output.writeText("mp4:$number")
            ScreenRemuxResult.Success(500L, output.length())
        }
        val pipeline: CapturePipeline?

        init {
            if (stage == Stage.ROOM_BLOCK) roomGates[1] = CountDownLatch(1)
            engine = ScreenContinuousSourceEngine(source, StorageStatus(free::get, 1_000L), remuxer,
                gaps, holder, encoderFactory = { _, _, _, _ ->
                    clock.addAndGet(1_000L)
                    Encoder(encoders.size + 1).also { encoders += it }
                }, onFloorTripped = { terminals.offer("floor") }, onFailed = { terminals.offer("failed") },
                nowProvider = clock::get, sleeper = {
                    if (blockControl) { controlEntered.countDown(); releaseControl.awaitUninterrupted() }
                    else wake.acquire()
                }, zoneProvider = { ZoneId.of("UTC") },
                stopJoinTimeoutMs = 20L)
            val writer = FileSpoolWriter(File(root, "spool").toPath())
            pipeline = if (usePipeline) CapturePipeline(Segmenter(ZoneId.of("UTC")), object : SpoolWriter {
                override fun seal(segment: SealedSegment, payloadBytes: PayloadBytesProvider): SealResult {
                    if (stage == Stage.SPOOL_BLOCK) { stageEntered.countDown(); spoolGate.awaitUninterrupted() }
                    if (stage == Stage.SPOOL_FAIL) { stageEntered.countDown(); error("spool unavailable") }
                    return writer.seal(segment, payloadBytes)
                }
            }, { segment, _, _ ->
                val number = numberOf(segment.payloads.single())
                roomEntered.offer(number)
                roomGates[number]?.let { stageEntered.countDown(); it.awaitUninterrupted() }
                if (stage == Stage.ROOM_FAIL) { stageEntered.countDown(); error("persistence unavailable") }
                persisted += segment
            }, object : PayloadBytesProvider {
                override fun open(payload: SegmentPayload) = engine.open(payload)
                override fun release(payload: SegmentPayload) {
                    val number = numberOf(payload)
                    engine.release(payload)
                    released.offer(number)
                }
                override fun discardUncommitted(payload: SegmentPayload) = engine.discardUncommitted(payload)
            }, listOf(object : ContinuousSourceEngine {
                override fun start(sink: EmissionSink) {
                    liveSink = EmissionSink { emission -> sink.emit(emission); submitted.offer(numberOf(emission.captureStartEpochMs)) }
                    engine.start(liveSink)
                }
                override fun stop() = engine.stop()
                override fun condition() = engine.condition()
            }), clock::get, 60_000L, diag = {
                if ("segment-seal-failed" in it || "segment-orphaned" in it) {
                    stageFailed.countDown(); failedFeeds.release()
                }
            }) else null
        }

        inner class Encoder(val number: Int) : ScreenEncoder {
            val full = AtomicBoolean(false)
            val draining = CountDownLatch(1)
            var stopGate: CountDownLatch? = null
            var drainFailure = false
            var stopped = false
            var closed = false
            var first = 0L
            lateinit var raw: File
            lateinit var index: File
            lateinit var firstFrame: (Long) -> Unit
            override fun inputSurface(): Any = this
            override fun sizeLimitReached() = full.get()
            override fun start(outputH264: File, outputIndex: File, onFirstFrame: (Long) -> Unit) {
                raw = outputH264; index = outputIndex; firstFrame = onFirstFrame
                raw.writeBytes(byteArrayOf()); index.writeText("")
            }
            fun frameAfterAttachment() {
                first = clock.get()
                raw.writeBytes(byteArrayOf(number.toByte())); index.writeText("0,1,1\n")
                firstFrame(first)
                clock.addAndGet(100L)
                attached.offer(this)
            }
            override fun stop(): Long {
                draining.countDown(); stopGate?.awaitUninterrupted(); stopped = true
                check(!drainFailure) { "drain incomplete" }
                return 1L
            }
            override fun release() { closed = true }
        }

        private fun numberOf(payload: SegmentPayload) = numberOf(payload.captureStartEpochMs)
        private fun numberOf(first: Long) = encoders.single { it.first == first }.number
        fun start() {
            assertTrue(holder.startSession(token = ScreenConsentToken(-1, Intent(), 1L)))
            if (pipeline != null) pipeline.start() else {
                liveSink = EmissionSink { submitted.offer(numberOf(it.captureStartEpochMs)) }
                engine.start(liveSink)
            }
        }
        fun freshConsent(token: Long) {
            assertTrue(holder.startSession(token = ScreenConsentToken(-1, Intent(), token)))
            engine.start(liveSink)
        }
        fun next(): Encoder = assertNotNull(attached.poll(3L, TimeUnit.SECONDS))
        fun roll(encoder: Encoder) { encoder.full.set(true); wake.release() }
        fun assertTwoProtected() {
            assertEquals(2, encoders.size)
            assertEquals(source.listFiles()!!.map { it.name }.toSet(), engine.inFlightRecordingIds())
            assertEquals(2, engine.inFlightRecordingIds().size)
            val touched = AtomicBoolean(false)
            val recovery = ScreenRecoveryManager(source, File(root, "guard-spool").toPath(), { _, _ -> emptySet() },
                AudioSpoolLookup { _, _, _, _, _, _, _, _ -> touched.set(true); AudioCommitStatus.NotFound },
                { _, _, _ -> touched.set(true) }, gaps,
                ScreenRemuxer { _, _, _, _, _ -> touched.set(true); ScreenRemuxResult.Failure("protected") })
            recovery.recover(engine.inFlightRecordingIds())
            assertFalse(touched.get())
        }
        fun restartCopyRecoversBothOnce() {
            // The old process is conceptually gone. Copy its now-closed originals
            // and spool to another root, so no writer/recovery publication can race.
            val restarted = File(root, "restarted").apply { mkdirs() }
            val copied = File(restarted, "source")
            source.copyRecursively(copied)
            val oldSpool = File(root, "spool")
            if (oldSpool.exists()) oldSpool.copyRecursively(File(restarted, "spool"))
            assertTrue(persisted.isEmpty())
            val adopted = mutableListOf<SealedSegment>()
            val recovery = ScreenRecoveryManager(copied, File(restarted, "spool").toPath(), { _, _ -> emptySet() },
                AudioSpoolLookup { _, _, _, _, _, _, _, _ -> AudioCommitStatus.NotFound },
                { segment, _, _ -> adopted += segment }, gaps, remuxer)
            recovery.recover(); recovery.recover()
            assertEquals(2, adopted.size)
            assertTrue(copied.listFiles()!!.isEmpty())
            val manifests = File(restarted, "spool").walkTopDown().filter { it.name == "manifest" }.toList()
            assertEquals(2, manifests.size)
        }
        override fun close() {
            remuxGates.values.forEach { it.countDown() }
            roomGates.values.forEach { it.countDown() }
            encoders.forEach { it.stopGate?.countDown() }
            spoolGate.countDown(); releaseControl.countDown(); wake.release(10)
            engine.stop(); pipeline?.stop(); holder.stopSession(); root.deleteRecursively()
        }
    }

    @Test
    fun failedDrainRetainsItsRawPrefixWithoutPublishingOrDeletingIt() {
        Bench().use { bench ->
            bench.start(); val first = bench.next()
            first.drainFailure = true
            bench.engine.stop()
            assertTrue(bench.unresolvedMarked.await(3L, TimeUnit.SECONDS))
            assertTrue(bench.unresolved.get())
            assertFalse(bench.holder.isSessionLive()); assertFalse(bench.engine.condition().running)
            assertTrue(first.raw.isFile && first.raw.length() > 0L)
            assertEquals(setOf(first.raw.parentFile.name), bench.engine.inFlightRecordingIds())
            assertTrue(bench.remuxEntered.isEmpty()); assertTrue(bench.submitted.isEmpty())
        }
    }

    @Test
    fun ownerStopKeepsItsOriginalCutoffWhenTheControlThreadIsDelayed() {
        Bench().use { bench ->
            bench.blockControl = true
            bench.start(); val first = bench.next()
            assertTrue(bench.controlEntered.await(3L, TimeUnit.SECONDS))
            val cutoff = bench.clock.get()
            bench.engine.stop()
            assertFalse(bench.holder.isSessionLive()); assertFalse(bench.engine.condition().running)
            bench.clock.addAndGet(10_000L)
            bench.releaseControl.countDown()
            assertEquals(1, bench.submitted.poll(3L, TimeUnit.SECONDS))
            assertEquals(cutoff, ScreenRecordInfo.parse(File(first.raw.parentFile, "record.txt").readText())!!.openEndEpochMs)
        }
    }

    @Test
    fun oldFinalizerSuccessOrFailureCannotStopQueuedFreshConsent() {
        for (fail in listOf(false, true)) {
            Bench().use { bench ->
                val gate = CountDownLatch(1)
                bench.remuxGates[1] = gate
                if (fail) bench.remuxFailures += 1
                bench.start(); bench.next(); bench.engine.stop()
                assertEquals(1, bench.remuxEntered.poll(3L, TimeUnit.SECONDS))
                bench.freshConsent(2L)
                assertFalse(bench.engine.condition().running)
                gate.countDown()
                val fresh = bench.next()
                assertEquals(2, fresh.number)
                assertTrue(fresh.raw.length() > 0L)
                assertTrue(bench.holder.isSessionLive()); assertTrue(bench.engine.condition().running)
            }
        }
    }

    @Test
    fun nextSurfaceProducesAFrameWhileOldRemuxIsBlockedAndTerminalPathsEndBeforeDrain() {
        for (trigger in listOf("owner", "system", "floor", "size", "geometry", "remux-failure")) {
            Bench().use { bench ->
                val oldRemux = CountDownLatch(1)
                bench.remuxGates[1] = oldRemux
                bench.start()
                val first = bench.next(); bench.roll(first)
                val second = bench.next()
                assertEquals(1, bench.remuxEntered.poll(3L, TimeUnit.SECONDS))
                assertTrue(second.raw.length() > 0L)
                assertNotNull(ScreenRecordInfo.parse(File(second.raw.parentFile, "record.txt").readText())!!.firstFrameEpochMs)
                bench.assertTwoProtected()
                assertEquals(1, bench.projections); assertEquals(1, bench.displays)
                second.stopGate = CountDownLatch(1)
                when (trigger) {
                    "owner" -> bench.engine.stop()
                    "system" -> bench.callback.onStop()
                    "floor" -> { bench.free.set(500L); bench.wake.release() }
                    "size" -> bench.roll(second)
                    "geometry" -> { bench.holder.onDisplayResized(1280, 720, 320); bench.wake.release() }
                    "remux-failure" -> { bench.remuxFailures += 1; oldRemux.countDown() }
                }
                assertTrue(second.draining.await(3L, TimeUnit.SECONDS), trigger)
                assertFalse(bench.holder.isSessionLive(), trigger)
                assertFalse(bench.engine.condition().running, trigger)
                val info = ScreenRecordInfo.parse(File(second.raw.parentFile, "record.txt").readText())!!
                val cutoff = assertNotNull(info.openEndEpochMs)
                bench.clock.addAndGet(10_000L)
                assertEquals(cutoff, ScreenRecordInfo.parse(File(second.raw.parentFile, "record.txt").readText())!!.openEndEpochMs)
                val stopStart = System.nanoTime(); bench.engine.stop()
                assertTrue(System.nanoTime() - stopStart < TimeUnit.SECONDS.toNanos(1L))
                bench.assertTwoProtected()
                assertFalse(second.closed)
                if (trigger == "floor") assertEquals(ReasonCode.STORAGE_FULL, bench.engine.reasonWhenOff())
                if (trigger in listOf("size", "geometry", "remux-failure"))
                    assertEquals(ReasonCode.SCREEN_CAPTURE_FAILED, bench.engine.reasonWhenOff())
            }
        }
    }

    @Test
    fun actualPipelineBlockedOrFailedCustodyRetainsCapacityAndRestartRecoversBothPrefixes() {
        for (stage in listOf(Stage.SPOOL_BLOCK, Stage.SPOOL_FAIL, Stage.ROOM_BLOCK, Stage.ROOM_FAIL)) {
            Bench(stage, usePipeline = true).use { bench ->
                bench.start(); bench.roll(bench.next()); val second = bench.next()
                assertTrue(bench.stageEntered.await(3L, TimeUnit.SECONDS), stage.name)
                assertEquals(1, bench.submitted.poll(3L, TimeUnit.SECONDS))
                if (stage in listOf(Stage.SPOOL_FAIL, Stage.ROOM_FAIL)) assertTrue(bench.stageFailed.await(3L, TimeUnit.SECONDS))
                bench.roll(second)
                assertEquals("failed", bench.terminals.poll(3L, TimeUnit.SECONDS))
                assertEquals(2, bench.submitted.poll(3L, TimeUnit.SECONDS))
                if (stage in listOf(Stage.SPOOL_FAIL, Stage.ROOM_FAIL))
                    assertTrue(bench.failedFeeds.tryAcquire(2, 3L, TimeUnit.SECONDS))
                bench.engine.stop()
                assertFalse(bench.holder.isSessionLive()); assertFalse(bench.engine.condition().running)
                bench.assertTwoProtected()
                assertTrue(bench.released.isEmpty())
                bench.restartCopyRecoversBothOnce()
                bench.freshConsent(2L)
                assertEquals("failed", bench.terminals.poll(3L, TimeUnit.SECONDS))
                bench.assertTwoProtected()
            }
        }
    }

    @Test
    fun actualDurableReleaseFreesExactlyOneSlotAndThirdPortionMakesProgress() {
        Bench(Stage.ROOM_BLOCK, usePipeline = true).use { bench ->
            bench.roomGates[2] = CountDownLatch(1)
            bench.start(); bench.roll(bench.next()); val second = bench.next()
            assertTrue(bench.stageEntered.await(3L, TimeUnit.SECONDS))
            bench.assertTwoProtected()
            bench.roomGates.getValue(1).countDown()
            assertEquals(1, bench.released.poll(3L, TimeUnit.SECONDS))
            assertEquals(setOf(second.raw.parentFile.name), bench.engine.inFlightRecordingIds())
            bench.roll(second); val third = bench.next()
            assertTrue(third.raw.length() > 0L)
            assertEquals(2, bench.roomEntered.poll(3L, TimeUnit.SECONDS).let { first ->
                if (first == 1) bench.roomEntered.poll(3L, TimeUnit.SECONDS) else first
            })
            assertEquals(setOf(second.raw.parentFile.name, third.raw.parentFile.name), bench.engine.inFlightRecordingIds())
            assertTrue(bench.holder.isSessionLive()); assertTrue(bench.engine.condition().running)
            assertEquals(1, bench.projections); assertEquals(1, bench.displays)
        }
    }

    @Test
    fun pendingCustodyAndDefinitiveRemuxFailureKeepCapacityAcrossFreshConsent() {
        for (failedRemux in listOf(false, true)) {
            Bench(if (failedRemux) Stage.NONE else Stage.ROOM_BLOCK, usePipeline = !failedRemux).use { bench ->
                val gate = CountDownLatch(1)
                if (failedRemux) { bench.remuxGates[1] = gate; bench.remuxFailures += 1 }
                else bench.roomGates[2] = CountDownLatch(1)
                bench.start(); bench.roll(bench.next()); bench.next()
                if (failedRemux) {
                    gate.countDown()
                    assertEquals("failed", bench.terminals.poll(3L, TimeUnit.SECONDS))
                } else {
                    assertTrue(bench.stageEntered.await(3L, TimeUnit.SECONDS))
                    bench.engine.stop()
                    assertEquals(1, bench.submitted.poll(3L, TimeUnit.SECONDS))
                }
                assertEquals(2, bench.submitted.poll(3L, TimeUnit.SECONDS))
                bench.engine.stop()
                bench.assertTwoProtected()
                bench.freshConsent(2L)
                assertEquals("failed", bench.terminals.poll(3L, TimeUnit.SECONDS))
                assertFalse(bench.holder.isSessionLive())
                bench.assertTwoProtected()
                if (!failedRemux) {
                    bench.roomGates.getValue(1).countDown()
                    assertEquals(1, bench.released.poll(3L, TimeUnit.SECONDS))
                    assertEquals(1, bench.engine.inFlightRecordingIds().size)
                    bench.freshConsent(3L)
                    val fresh = bench.next()
                    assertEquals(3, fresh.number)
                    val generation = bench.holder.generation()
                    bench.roomGates.getValue(2).countDown()
                    assertEquals(2, bench.released.poll(3L, TimeUnit.SECONDS))
                    assertTrue(bench.holder.isSessionLive()); assertTrue(bench.engine.condition().running)
                    assertEquals(generation, bench.holder.generation())
                }
            }
        }
    }

    private companion object {
        fun CountDownLatch.awaitUninterrupted() {
            while (true) {
                try { await(); return } catch (_: InterruptedException) {}
            }
        }
    }
}
