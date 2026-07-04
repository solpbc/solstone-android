// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.location

import app.solstone.core.segment.SegmentPayload
import app.solstone.core.segment.Segmenter
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.SourceEmission
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
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
        assertEquals(BASE_EPOCH_MS, first.captureStartEpochMs)
        val segmenter = Segmenter(ZoneId.of("UTC"))
        val sealed = sink.emissions.take(1).flatMap { segmenter.feed(it).sealed } + segmenter.flush().sealed
        assertFalse(sealed.flatMap { it.gaps }.any { it.kind == "late_emission" })
        assertTrue(sealed.single().payloads.all { it.captureStartEpochMs == BASE_EPOCH_MS })
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
        assertEquals(BASE_EPOCH_MS, first.captureStartEpochMs)
        assertEquals(now, first.captureEndEpochMs)
        val segmenter = Segmenter(ZoneId.of("UTC"))
        segmenter.feed(first)
        val sealed = segmenter.flush().sealed.single()
        assertEquals(BASE_EPOCH_MS, sealed.wireKeys.startEpochMs)
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
    }
}
