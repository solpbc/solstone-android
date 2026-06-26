// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.metadata

import app.solstone.core.model.SourceKind
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceEmission
import app.solstone.testing.FakeBatterySource
import app.solstone.testing.FakeImuSensorPort
import app.solstone.testing.FakeMetadataScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhotoMetadataEngineTest {
    @Test
    fun exactlyOneLinePerPhotoAscendingEvenWhenSnapshotsFinishOutOfOrder() {
        val fixture = startedFixture(startEpochMs = BASE)

        fixture.engine.onCameraEmission(cameraEmission(BASE + 20_000L))
        fixture.engine.onCameraEmission(cameraEmission(BASE + 5_000L))
        fixture.engine.onCameraEmission(cameraEmission(BASE + 12_000L))
        fixture.imu.emitLinear(2, LinearAccelerationSample(0f, 0f, 3f, BASE + 12_010L))
        fixture.imu.emitLinear(0, LinearAccelerationSample(0f, 4f, 0f, BASE + 20_010L))
        fixture.imu.emitLinear(1, LinearAccelerationSample(5f, 0f, 0f, BASE + 5_010L))

        fixture.scheduler.advanceBy(PhotoMetadataContract.SNAPSHOT_MS)
        fixture.scheduler.advanceTo(BASE + PhotoMetadataContract.WINDOW_MS)

        val lines = fixture.singlePayloadLines()
        assertEquals(3, lines.size)
        assertEquals(listOf(BASE + 5_000L, BASE + 12_000L, BASE + 20_000L), lines.map(::tsFromLine))
        assertTrue(lines.all { it.contains("\"motion\"") })
    }

    @Test
    fun zeroPhotoWindowEmitsNoMetadataPayload() {
        val fixture = startedFixture(startEpochMs = BASE)

        fixture.scheduler.advanceTo(BASE + PhotoMetadataContract.WINDOW_MS)

        assertTrue(fixture.emissions.isEmpty())
    }

    @Test
    fun dutyCyclesImuAndIsIdleBetweenPhotos() {
        val fixture = startedFixture(startEpochMs = BASE)

        fixture.engine.onCameraEmission(cameraEmission(BASE + 1_000L))
        assertEquals(1, fixture.imu.activeRegistrations)
        fixture.scheduler.advanceBy(PhotoMetadataContract.SNAPSHOT_MS)
        assertEquals(0, fixture.imu.activeRegistrations)
        fixture.scheduler.advanceTo(BASE + 14_999L)
        assertEquals(0, fixture.imu.activeRegistrations)

        fixture.engine.onCameraEmission(cameraEmission(BASE + 15_000L))

        assertEquals(1, fixture.imu.activeRegistrations)
        assertEquals(2, fixture.imu.startCount)
    }

    @Test
    fun inFlightSnapshotAtWindowFlushOmitsMotion() {
        val fixture = startedFixture(startEpochMs = BASE + PhotoMetadataContract.WINDOW_MS - 1_000L)

        fixture.engine.onCameraEmission(cameraEmission(BASE + PhotoMetadataContract.WINDOW_MS - 500L))
        assertEquals(1, fixture.imu.activeRegistrations)
        fixture.scheduler.advanceTo(BASE + PhotoMetadataContract.WINDOW_MS)

        val line = fixture.singlePayloadLines().single()
        assertEquals(BASE + PhotoMetadataContract.WINDOW_MS - 500L, tsFromLine(line))
        assertFalse(line.contains("\"motion\""))
        assertEquals(0, fixture.imu.activeRegistrations)
    }

    @Test
    fun inFlightSnapshotAtStopOmitsMotionAndStillEmits() {
        val fixture = startedFixture(startEpochMs = BASE)

        fixture.engine.onCameraEmission(cameraEmission(BASE + 1_000L))
        assertEquals(1, fixture.imu.activeRegistrations)

        fixture.engine.stop()

        val line = fixture.singlePayloadLines().single()
        assertEquals(BASE + 1_000L, tsFromLine(line))
        assertFalse(line.contains("\"motion\""))
        assertEquals(0, fixture.imu.activeRegistrations)
    }

    @Test
    fun cameraTapAfterStopRegistersNoImuAndEmitsNoFurtherMetadata() {
        val fixture = startedFixture(startEpochMs = BASE)

        fixture.engine.onCameraEmission(cameraEmission(BASE + 1_000L))
        fixture.engine.stop()
        val startCountAfterStop = fixture.imu.startCount
        val emissionsAfterStop = fixture.emissions.size

        fixture.engine.onCameraEmission(cameraEmission(BASE + 2_000L))
        fixture.scheduler.advanceBy(PhotoMetadataContract.SNAPSHOT_MS)
        fixture.scheduler.advanceTo(BASE + PhotoMetadataContract.WINDOW_MS)

        assertEquals(startCountAfterStop, fixture.imu.startCount)
        assertEquals(0, fixture.imu.activeRegistrations)
        assertEquals(emissionsAfterStop, fixture.emissions.size)
    }

    private fun startedFixture(startEpochMs: Long): Fixture {
        val scheduler = FakeMetadataScheduler(startEpochMs)
        val battery = FakeBatterySource(BatterySnapshot(level = 77, status = BatteryStatus.FULL, tempC = 29.0))
        val imu = FakeImuSensorPort()
        val engine = PhotoMetadataEngine(scheduler, battery, imu)
        val emissions = mutableListOf<SourceEmission>()
        engine.start { emissions += it }
        return Fixture(engine, scheduler, imu, emissions)
    }

    private fun Fixture.singlePayloadLines(): List<String> {
        assertEquals(1, emissions.size)
        val emission = emissions.single()
        assertEquals(PhotoMetadataContract.SOURCE_ID, emission.sourceId)
        assertEquals(MAIN_STREAM, emission.stream)
        val ref = emission.payloadRefs.single()
        assertEquals(PhotoMetadataContract.PAYLOAD_NAME, ref.name)
        assertEquals(PhotoMetadataContract.MEDIA_TYPE, ref.mediaType)
        val bytes = engine.open(
            SegmentPayload(
                sourceId = emission.sourceId,
                ref = ref,
                captureStartEpochMs = emission.captureStartEpochMs,
                captureEndEpochMs = emission.captureEndEpochMs,
            ),
        ).readBytes()
        assertEquals(ref.byteSize, bytes.size.toLong())
        return bytes.decodeToString().lineSequence().filter { it.isNotEmpty() }.toList()
    }

    private fun cameraEmission(ts: Long): SourceEmission =
        SourceEmission(
            sourceId = "camera",
            stream = MAIN_STREAM,
            sourceKind = SourceKind.OBSERVER,
            captureStartEpochMs = ts,
            captureEndEpochMs = ts + 10L,
            payloadRefs = listOf(PayloadRef("camera-$ts.jpg", "image/jpeg", 10L, null)),
            metadata = emptyMap(),
            gaps = emptyList(),
        )

    private fun tsFromLine(line: String): Long {
        val prefix = "{\"ts\":"
        assertTrue(line.startsWith(prefix))
        return line.substringAfter(prefix).substringBefore(',').substringBefore('}').toLong()
    }

    private data class Fixture(
        val engine: PhotoMetadataEngine,
        val scheduler: FakeMetadataScheduler,
        val imu: FakeImuSensorPort,
        val emissions: MutableList<SourceEmission>,
    )

    private companion object {
        const val BASE = 1_772_582_400_000L
    }
}
