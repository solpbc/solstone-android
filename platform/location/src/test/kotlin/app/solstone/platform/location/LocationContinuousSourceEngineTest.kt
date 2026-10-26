// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.location

import app.solstone.core.model.SourceKind
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.segment.Segmenter
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceEmission
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.SealState
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationContinuousSourceEngineTest {
    @Test
    fun locationFirstEmissionUsesCurrentWallGrid() {
        val sink = CapturingSink()
        var now = BASE_EPOCH_MS + 47_000L
        val engine = LocationContinuousSourceEngine(
            source = FixedLocationSource(),
            nowProvider = { now },
            sleeper = {
                now = BASE_EPOCH_MS + LocationContinuousSourceEngine.WINDOW_MS
            },
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val first = sink.emissions.first()
        assertEquals(BASE_EPOCH_MS + 47_000L, first.captureStartEpochMs)
        val segmenter = Segmenter(ZoneId.of("UTC"))
        val sealed = sink.emissions.take(1).flatMap { segmenter.feed(it).sealed } + segmenter.flush().sealed
        assertFalse(sealed.flatMap { it.gaps }.any { it.kind == "late_emission" })
        assertTrue(sealed.single().payloads.all { it.captureStartEpochMs == BASE_EPOCH_MS + 47_000L })
    }

    @Test
    fun startedExactlyOnBoundaryEmitsFullWindow() {
        val sink = CapturingSink()
        var now = BASE_EPOCH_MS
        val engine = LocationContinuousSourceEngine(
            source = FixedLocationSource(),
            nowProvider = { now },
            sleeper = {
                now = BASE_EPOCH_MS + LocationContinuousSourceEngine.WINDOW_MS
            },
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val first = sink.emissions.first()
        assertEquals(BASE_EPOCH_MS, first.captureStartEpochMs)
        assertEquals(BASE_EPOCH_MS + LocationContinuousSourceEngine.WINDOW_MS, first.captureEndEpochMs)
    }

    @Test
    fun stopInterruptEmitsPartialInOriginalGridCell() {
        val sink = CapturingSink()
        val now = BASE_EPOCH_MS + 47_000L
        val engine = LocationContinuousSourceEngine(
            source = FixedLocationSource(),
            nowProvider = { now },
            sleeper = { throw InterruptedException() },
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val first = sink.emissions.first()
        assertEquals(BASE_EPOCH_MS + 47_000L, first.captureStartEpochMs)
        assertEquals(now, first.captureEndEpochMs)
        val segmenter = Segmenter(ZoneId.of("UTC"))
        segmenter.feed(first)
        val sealed = segmenter.flush().sealed.single()
        assertEquals(BASE_EPOCH_MS + 47_000L, sealed.wireKeys.startEpochMs)
        assertEquals(BASE_EPOCH_MS, segmenter.windowStart(first.captureStartEpochMs))
    }

    @Test
    fun locationPayloadCacheEvictsOldNeverOpenedWindows() {
        val sink = CapturingSink()
        var now = BASE_EPOCH_MS
        val engine = LocationContinuousSourceEngine(
            source = FixedLocationSource(),
            nowProvider = { now },
            sleeper = {
                now += LocationContinuousSourceEngine.WINDOW_MS
            },
        )

        engine.start(sink)
        waitForEmissions(sink, LocationContinuousSourceEngine.MAX_CACHED_WINDOWS + 1)
        engine.stop()

        val oldest = payloadFor(sink.emissions.first())
        val newest = payloadFor(sink.emissions.last())
        assertFailsWith<IllegalArgumentException> { engine.open(oldest) }
        assertTrue(engine.open(newest).use { it.readBytes().isNotEmpty() })
    }

    @Test
    fun releaseRemovesDroppedBytes() {
        val sink = CapturingSink()
        var now = BASE_EPOCH_MS
        val engine = LocationContinuousSourceEngine(
            source = FixedLocationSource(),
            nowProvider = { now },
            sleeper = {
                now += LocationContinuousSourceEngine.WINDOW_MS
            },
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val payload = payloadFor(sink.emissions.first())
        engine.release(payload)
        assertFailsWith<IllegalArgumentException> { engine.open(payload) }
    }

    @Test
    fun workerDeathEmitsTerminalGapAndDiagAndReportsNotRunning() {
        val sink = CapturingSink()
        val diags = CopyOnWriteArrayList<String>()
        val engine = LocationContinuousSourceEngine(
            source = FixedLocationSource(),
            nowProvider = { BASE_EPOCH_MS },
            sleeper = { throw IllegalStateException("sleep failed") },
            diag = diags::add,
        )

        engine.start(sink)
        waitForEmissions(sink, 1)

        assertFalse(engine.condition().running)
        assertEquals("engine_failed type=IllegalStateException message=sleep failed", sink.emissions.single().gaps.single().detail)
        assertTrue("capture event=engine-failed source=location type=IllegalStateException message=sleep failed" in diags)
    }

    private fun payloadFor(emission: SourceEmission): SegmentPayload =
        SegmentPayload(
            sourceId = emission.sourceId,
            ref = emission.payloadRefs.single(),
            captureStartEpochMs = emission.captureStartEpochMs,
            captureEndEpochMs = emission.captureEndEpochMs,
        )

    @Test
    fun samplesAtTheDeclaredIntervalRatherThanEverySleepSlice() {
        // One reading per declared interval, not one per sleep slice. Before the slice loop was
        // fixed, SLEEP_SLICE_MS was the real interval and this window carried 300 rows.
        val rows = firstWindowRowCount { nowEpochMs -> LocationFix("network", nowEpochMs, 1.0, 2.0, 5.0, 0L) }
        assertEquals(
            (LocationContinuousSourceEngine.WINDOW_MS / LocationContinuousSourceEngine.SAMPLE_EVERY_MS).toInt(),
            rows,
        )
    }

    @Test
    fun anUnchangedFixIsRecordedOncePerWindow() {
        val rows = firstWindowRowCount { nowEpochMs ->
            LocationFix("network", STATIONARY_FIX_MS, 1.0, 2.0, 5.0, nowEpochMs - STATIONARY_FIX_MS)
        }
        assertEquals(1, rows)
    }

    @Test
    fun eachWindowIssuesOneFreshFixRequest() {
        val source = ScriptedLocationSource()
        val sink = CapturingSink()
        var now = BASE_EPOCH_MS
        val engine = LocationContinuousSourceEngine(
            source = source,
            nowProvider = { now },
            sleeper = { now += LocationContinuousSourceEngine.WINDOW_MS },
        )

        engine.start(sink)
        waitForEmissions(sink, 2)
        engine.stop()

        assertTrue(source.requestCount.get() >= 2)
        assertEquals(listOf("request", "cancel", "request", "cancel"), source.events.toList().take(4))
    }

    @Test
    fun freshFixIsRecordedInTheRequestingWindow() {
        val fresh = LocationFix("network", BASE_EPOCH_MS + 1_000L, 51.5, -0.1, 25.0, 0L)
        val source = ScriptedLocationSource(
            last = { null },
            onRequest = { onResult -> onResult(fresh) },
        )
        val body = firstWindowBody(source)

        assertEquals(1, source.requestCount.get())
        assertTrue("\"timestamp\":${fresh.timestampEpochMs}" in body)
        assertTrue("\"lat\":51.5" in body)
        assertEquals(1, body.trim().lines().size)
    }

    @Test
    fun freshFixMatchingLastKnownTimestampIsNotASecondRow() {
        val shared = LocationFix("network", STATIONARY_FIX_MS, 1.0, 2.0, 5.0, 0L)
        val source = ScriptedLocationSource(
            last = { nowEpochMs -> shared.copy(fixAgeMs = nowEpochMs - STATIONARY_FIX_MS) },
            onRequest = { onResult -> onResult(shared) },
        )

        assertEquals(1, firstWindowBody(source).trim().lines().size)
    }

    @Test
    fun distinctLastKnownAndFreshFixAreBothRecorded() {
        val lastKnown = LocationFix("network", STATIONARY_FIX_MS, 1.0, 2.0, 5.0, 0L)
        val fresh = LocationFix("network", BASE_EPOCH_MS + 2_000L, 3.0, 4.0, 15.0, 0L)
        val source = ScriptedLocationSource(
            last = { nowEpochMs -> lastKnown.copy(fixAgeMs = nowEpochMs - STATIONARY_FIX_MS) },
            onRequest = { onResult -> onResult(fresh) },
        )
        val lines = firstWindowBody(source).trim().lines()

        assertTrue(lines.any { "\"timestamp\":${lastKnown.timestampEpochMs}" in it })
        assertTrue(lines.any { "\"timestamp\":${fresh.timestampEpochMs}" in it })
    }

    @Test
    fun timeoutWithNoLastKnownSealsExistingGap() {
        val source = ScriptedLocationSource(last = { null })
        val sink = CapturingSink()
        var now = BASE_EPOCH_MS
        val engine = LocationContinuousSourceEngine(
            source = source,
            nowProvider = { now },
            sleeper = { now = BASE_EPOCH_MS + LocationContinuousSourceEngine.WINDOW_MS },
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val first = sink.emissions.first()
        assertEquals(emptyList(), first.payloadRefs)
        assertEquals("location_gap", first.gaps.single().kind)
        assertEquals(NoFixReason.NO_FIX.detail, first.gaps.single().detail)
        assertTrue(source.requestCount.get() >= 1)
        assertTrue(source.cancelCount.get() >= 1)
    }

    @Test
    fun postCancelFreshFixIsNotRecorded() {
        val late = LocationFix("network", BASE_EPOCH_MS + 9_000L, 10.0, 11.0, 8.0, 0L)
        val source = ScriptedLocationSource(last = { null })
        val sink = CapturingSink()
        var now = BASE_EPOCH_MS
        val engine = LocationContinuousSourceEngine(
            source = source,
            nowProvider = { now },
            sleeper = { now += LocationContinuousSourceEngine.WINDOW_MS },
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        source.callbacks.first().invoke(late)
        waitForEmissions(sink, 2)
        engine.stop()

        assertEquals(emptyList(), sink.emissions[0].payloadRefs)
        assertEquals(emptyList(), sink.emissions[1].payloadRefs)
        assertEquals("location_gap", sink.emissions[1].gaps.single().kind)
    }

    private fun utc(hour: Int, minute: Int, second: Int, millis: Int = 0): Long =
        java.time.LocalDateTime.of(2026, 3, 4, hour, minute, second)
            .atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() + millis

    @Test
    fun locationAndAudioSpoolUnderTheReportedStart() {
        val sink = CapturingSink()
        var now = utc(8, 17, 13)
        val windowMs = LocationContinuousSourceEngine.WINDOW_MS
        val stopLatch = CountDownLatch(1)
        val engine = LocationContinuousSourceEngine(
            source = FixedLocationSource(),
            nowProvider = { now },
            sleeper = {
                val floor = Math.floorDiv(now, windowMs) * windowMs
                if (floor < utc(8, 25, 0)) {
                    now = floor + windowMs
                } else {
                    stopLatch.countDown()
                    Thread.sleep(60_000L)
                }
            },
        )
        engine.start(sink)
        waitForEmissions(sink, 2)
        assertTrue(stopLatch.await(5, TimeUnit.SECONDS), "timed out waiting for third window sleeper")
        engine.stop()

        assertEquals(2, sink.emissions.size)
        assertEquals(utc(8, 17, 13), sink.emissions[0].captureStartEpochMs)
        assertEquals(utc(8, 20, 0), sink.emissions[1].captureStartEpochMs)

        fun audioEmission(start: Long, end: Long) = SourceEmission(
            sourceId = "audio",
            stream = MAIN_STREAM,
            sourceKind = SourceKind.OBSERVER,
            captureStartEpochMs = start,
            captureEndEpochMs = end,
            payloadRefs = listOf(PayloadRef("audio.m4a", "audio/mp4", 16L, null)),
            metadata = emptyMap(),
            gaps = emptyList(),
            independentTimeline = false,
        )

        val audio1 = audioEmission(utc(8, 17, 13), utc(8, 20, 0))
        val audio2 = audioEmission(utc(8, 20, 0), utc(8, 25, 0))
        val audio3 = audioEmission(utc(8, 25, 0), utc(8, 25, 30))

        val segmenter = Segmenter(ZoneId.of("UTC"))
        val seals = mutableListOf<SealedSegment>()
        seals += segmenter.feed(audio1).sealed
        seals += segmenter.feed(sink.emissions[0]).sealed
        seals += segmenter.feed(audio2).sealed
        seals += segmenter.feed(sink.emissions[1]).sealed
        seals += segmenter.feed(audio3).sealed
        seals += segmenter.flush().sealed

        assertEquals(3, seals.size)

        // 1: 081713_167 start 08:17:13 end 08:20:00, payloads audio.m4a and location.jsonl
        val seg1 = seals[0]
        assertEquals("081713_167", seg1.key.segment)
        assertEquals(utc(8, 17, 13), seg1.wireKeys.startEpochMs)
        assertEquals(utc(8, 20, 0), seg1.wireKeys.endEpochMs)
        assertEquals(setOf("audio.m4a", "location.jsonl"), seg1.payloads.map { it.ref.name }.toSet())

        // 2: 082000_300 start 08:20:00 end 08:25:00, both
        val seg2 = seals[1]
        assertEquals("082000_300", seg2.key.segment)
        assertEquals(utc(8, 20, 0), seg2.wireKeys.startEpochMs)
        assertEquals(utc(8, 25, 0), seg2.wireKeys.endEpochMs)
        assertEquals(setOf("audio.m4a", "location.jsonl"), seg2.payloads.map { it.ref.name }.toSet())

        // 3: 082500_30 start 08:25:00 end 08:25:30, only audio.m4a
        val seg3 = seals[2]
        assertEquals("082500_30", seg3.key.segment)
        assertEquals(utc(8, 25, 0), seg3.wireKeys.startEpochMs)
        assertEquals(utc(8, 25, 30), seg3.wireKeys.endEpochMs)
        assertEquals(listOf("audio.m4a"), seg3.payloads.map { it.ref.name })

        val spoolDir = Files.createTempDirectory("location-spool")
        try {
            val writer = FileSpoolWriter(spoolDir)
            val audioBytes = "fake-audio-bytes".encodeToByteArray()
            val provider = object : PayloadBytesProvider {
                override fun open(payload: SegmentPayload): java.io.InputStream =
                    when (payload.sourceId) {
                        "location" -> engine.open(payload)
                        "audio" -> ByteArrayInputStream(audioBytes)
                        else -> error("unexpected source: ${payload.sourceId}")
                    }
                override fun release(payload: SegmentPayload) {
                    if (payload.sourceId == "location") engine.release(payload)
                }
            }

            val r1 = writer.seal(seg1, provider)
            assertEquals(SealState.SEALED, r1.state)
            val dir1 = checkNotNull(r1.directory)
            val dir1Files = Files.list(dir1).use { s -> s.map { it.fileName.toString() }.toList() }
            assertTrue(dir1Files.contains("audio.m4a") && dir1Files.contains("location.jsonl"))

            val r2 = writer.seal(seg2, provider)
            assertEquals(SealState.SEALED, r2.state)
            val dir2 = checkNotNull(r2.directory)
            val dir2Files = Files.list(dir2).use { s -> s.map { it.fileName.toString() }.toList() }
            assertTrue(dir2Files.contains("audio.m4a") && dir2Files.contains("location.jsonl"))

            val r3 = writer.seal(seg3, provider)
            assertEquals(SealState.SEALED, r3.state)
            val dir3 = checkNotNull(r3.directory)
            val dir3Files = Files.list(dir3).use { s -> s.map { it.fileName.toString() }.toList() }
            assertTrue(dir3Files.contains("audio.m4a"))
            assertFalse(dir3Files.contains("location.jsonl"))
        } finally {
            spoolDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun cachedFixTimestampDoesNotMoveCaptureStart() {
        val sink = CapturingSink()
        var now = utc(8, 17, 13)
        val cachedTimestamp = utc(8, 7, 13)
        val source = ScriptedLocationSource(
            last = {
                LocationFix(
                    provider = "gps",
                    timestampEpochMs = cachedTimestamp,
                    lat = 39.7392,
                    lon = -104.9903,
                    accuracyMeters = 8.0,
                    fixAgeMs = now - cachedTimestamp,
                )
            },
            reason = NoFixReason.NO_FIX,
            onRequest = {},
        )
        val engine = LocationContinuousSourceEngine(
            source = source,
            nowProvider = { now },
            sleeper = { now = utc(8, 20, 0) },
        )
        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val emission = sink.emissions.single()
        assertEquals(utc(8, 17, 13), emission.captureStartEpochMs)
        assertTrue(emission.captureStartEpochMs != utc(8, 15, 0))
        assertTrue(emission.captureStartEpochMs != cachedTimestamp)

        val payload = payloadFor(emission)
        val body = engine.open(payload).use { it.readBytes().decodeToString() }
        assertTrue(body.contains("\"timestamp\":$cachedTimestamp"))
    }

    /**
     * Runs one full window against a fake clock and returns the row count of its payload.
     * The body is read inside emit, before the engine's bounded payload cache can evict it.
     */
    private fun firstWindowRowCount(fix: (Long) -> LocationFix): Int {
        var now = BASE_EPOCH_MS
        val bodies = CopyOnWriteArrayList<String>()
        lateinit var engine: LocationContinuousSourceEngine
        val sink = object : EmissionSink {
            override fun emit(emission: SourceEmission) {
                if (emission.payloadRefs.isNotEmpty() && bodies.isEmpty()) {
                    bodies += engine.open(payloadFor(emission)).readBytes().decodeToString()
                }
                throw InterruptedException()
            }
        }
        engine = LocationContinuousSourceEngine(
            source = object : LocationSource {
                override fun lastFix(nowEpochMs: Long): LocationFix = fix(nowEpochMs)

                override fun noFixReason(): NoFixReason = NoFixReason.NO_FIX

                override fun requestFreshFix(onResult: (LocationFix?) -> Unit): FreshFixCancel =
                    FreshFixCancel { }
            },
            nowProvider = { now },
            sleeper = { now += it },
        )

        engine.start(sink)
        waitUntil { bodies.isNotEmpty() }
        engine.stop()
        if (bodies.isEmpty()) throw AssertionError("no payload was emitted")
        return bodies.first().trim().lines().size
    }

    private fun firstWindowBody(source: LocationSource): String {
        var now = BASE_EPOCH_MS
        val bodies = CopyOnWriteArrayList<String>()
        lateinit var engine: LocationContinuousSourceEngine
        val sink = object : EmissionSink {
            override fun emit(emission: SourceEmission) {
                if (emission.payloadRefs.isNotEmpty() && bodies.isEmpty()) {
                    bodies += engine.open(payloadFor(emission)).readBytes().decodeToString()
                }
                throw InterruptedException()
            }
        }
        engine = LocationContinuousSourceEngine(
            source = source,
            nowProvider = { now },
            sleeper = { now += it },
        )

        engine.start(sink)
        waitUntil { bodies.isNotEmpty() }
        engine.stop()
        if (bodies.isEmpty()) throw AssertionError("no payload was emitted")
        return bodies.first()
    }

    private fun waitUntil(predicate: () -> Boolean) {
        repeat(200) {
            if (predicate()) return
            Thread.sleep(5L)
        }
    }

    private class FixedLocationSource : LocationSource {
        override fun lastFix(nowEpochMs: Long): LocationFix =
            LocationFix(
                provider = "gps",
                timestampEpochMs = nowEpochMs,
                lat = 39.7392,
                lon = -104.9903,
                accuracyMeters = 8.0,
                fixAgeMs = 0L,
            )

        override fun noFixReason(): NoFixReason = NoFixReason.NO_FIX

        override fun requestFreshFix(onResult: (LocationFix?) -> Unit): FreshFixCancel =
            FreshFixCancel { }
    }

    private class ScriptedLocationSource(
        private val last: (Long) -> LocationFix? = { null },
        private val reason: NoFixReason = NoFixReason.NO_FIX,
        private val onRequest: ((LocationFix?) -> Unit) -> Unit = {},
    ) : LocationSource {
        val requestCount = AtomicInteger()
        val cancelCount = AtomicInteger()
        val events = CopyOnWriteArrayList<String>()
        val callbacks = CopyOnWriteArrayList<(LocationFix?) -> Unit>()

        override fun lastFix(nowEpochMs: Long): LocationFix? = last(nowEpochMs)

        override fun noFixReason(): NoFixReason = reason

        override fun requestFreshFix(onResult: (LocationFix?) -> Unit): FreshFixCancel {
            requestCount.incrementAndGet()
            events += "request"
            callbacks += onResult
            onRequest(onResult)
            return FreshFixCancel {
                cancelCount.incrementAndGet()
                events += "cancel"
            }
        }
    }

    private class CapturingSink : EmissionSink {
        val emissions = CopyOnWriteArrayList<SourceEmission>()

        override fun emit(emission: SourceEmission) {
            emissions += emission
        }
    }

    private fun waitForEmissions(sink: CapturingSink, count: Int) {
        repeat(200) {
            if (sink.emissions.size >= count) return
            Thread.sleep(5L)
        }
        throw AssertionError("expected $count emissions, got ${sink.emissions.size}")
    }

    private companion object {
        const val BASE_EPOCH_MS = 1_772_582_400_000L
        const val STATIONARY_FIX_MS = BASE_EPOCH_MS - 30_000L
    }
}
