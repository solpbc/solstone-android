// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceIntakeTest {
    @Test
    fun startedStaysOnTheApplyClockAndLastOnlyMovesForward() {
        val first = advanceIntake(null, add = 5, materialAt = 1_000, now = 5_000, touch = true)
        assertEquals(IntakeReading(5, startedAt = 5_000, lastAt = 1_000), first)
        val older = advanceIntake(first, add = 2, materialAt = 500, now = 9_000, touch = true)
        assertEquals(IntakeReading(7, startedAt = 5_000, lastAt = 1_000), older)
        val newer = advanceIntake(older, add = 0, materialAt = 8_000, now = 9_000, touch = true)
        assertEquals(IntakeReading(7, startedAt = 5_000, lastAt = 8_000), newer)
        assertEquals(newer, advanceIntake(newer, add = 4, materialAt = 9_000, now = 9_000, touch = false))
    }

    @Test
    fun aStationaryFixStillOpensTheLocationRow() {
        val delta = locationDelta(listOf(fix(at = 100, lat = 10.0, accuracy = 50.0)), anchor = null)
        assertEquals(0L, delta?.add)
        assertEquals(100L, delta?.materialAt)
        assertEquals(100L, delta?.anchor?.atEpochMs)
        val reading = advanceIntake(null, delta!!.add, delta.materialAt, now = 9_000, touch = true)
        assertEquals(0L, reading?.total)
        assertEquals(9_000L, reading?.startedAt)
    }

    @Test
    fun oneKilometreCountsAndAStepWithinAccuracyDoesNot() {
        val north = latitudeDegrees(1_000.0)
        val moved = locationDelta(
            listOf(
                fix(at = 1, lat = 0.0, accuracy = 50.0),
                fix(at = 2, lat = north, accuracy = 50.0),
            ),
            anchor = null,
        )
        assertNear(1_000.0, moved?.add)
        val exact = haversineMetres(0.0, 0.0, north, 0.0)
        val held = locationDelta(
            listOf(
                fix(at = 1, lat = 0.0, accuracy = exact),
                fix(at = 2, lat = north, accuracy = exact),
            ),
            anchor = null,
        )
        assertEquals(0L, held?.add)
        assertEquals(2L, held?.anchor?.atEpochMs)
    }

    @Test
    fun aMissingAccuracyAddsNothingAndTheAnchorStillAdvances() {
        val north = latitudeDegrees(1_000.0)
        val delta = locationDelta(
            listOf(
                fix(at = 1, lat = 0.0, accuracy = null),
                fix(at = 2, lat = north, accuracy = 50.0),
            ),
            anchor = null,
        )
        assertEquals(0L, delta?.add)
        assertEquals(2L, delta?.anchor?.atEpochMs)
        assertEquals(north, delta?.anchor?.lat)
    }

    @Test
    fun anOlderSegmentCountsItsOwnStepsAndLeavesTheAnchor() {
        val anchor = fix(at = 100, lat = 0.0, accuracy = 5.0)
        val north = latitudeDegrees(1_000.0)
        val delta = locationDelta(
            listOf(
                fix(at = 10, lat = 1.0, accuracy = 5.0),
                fix(at = 20, lat = 1.0 + north, accuracy = 5.0),
            ),
            anchor = anchor,
        )
        assertNear(1_000.0, delta?.add)
        assertNull(delta?.anchor)
        assertEquals(20L, delta?.materialAt)
    }

    @Test
    fun aBoundaryStepUsesTheAnchorOnlyWhenTheSegmentIsNewer() {
        val anchor = fix(at = 1, lat = 0.0, accuracy = 5.0)
        val north = latitudeDegrees(1_000.0)
        val delta = locationDelta(listOf(fix(at = 2, lat = north, accuracy = 5.0)), anchor)
        assertNear(1_000.0, delta?.add)
        assertEquals(2L, delta?.anchor?.atEpochMs)
        val sameInstant = locationDelta(listOf(fix(at = 1, lat = north, accuracy = 5.0)), anchor)
        assertEquals(0L, sameInstant?.add)
        assertNull(sameInstant?.anchor)
    }

    @Test
    fun aMixedSegmentCountsEachSourceOnItsOwn() {
        val audio = file("audio", "audio.m4a", start = 0, end = 5_000, bytes = 10)
        val short = file("audio", "gap.m4a", start = 0, end = 400, bytes = 10)
        val photos = (1..3).map { file("camera", "camera-$it.jpg", start = 0, end = 1_000, bytes = 20) }
        val emptyPhoto = file("camera", "camera-empty.jpg", start = 0, end = 1_000, bytes = 0)
        val calendar = file("calendar", "calendar_1.jsonl", start = 0, end = 60_000, bytes = 30)
        val north = latitudeDegrees(1_000.0)
        val deltas = segmentIntakeDeltas(
            listOf(audio, short, emptyPhoto) + photos + calendar,
            listOf(fix(at = 1, lat = 0.0, accuracy = 5.0), fix(at = 2, lat = north, accuracy = 5.0)),
            anchor = null,
        ).associateBy { it.sourceId }
        assertEquals(5L, deltas.getValue(INTAKE_AUDIO).add)
        assertEquals(5_000L, deltas.getValue(INTAKE_AUDIO).materialAt)
        assertEquals(3L, deltas.getValue(INTAKE_CAMERA).add)
        assertNear(1_000.0, deltas.getValue(INTAKE_LOCATION).add)
        assertNull(deltas[INTAKE_CALENDAR])
        assertNull(deltas[INTAKE_SCREEN])
        assertNull(deltas[INTAKE_SCREENSHOTS])
    }

    @Test
    fun aScreenshotFileCountsOnceAndAnEmptyOneDoesNot() {
        val deltas = segmentIntakeDeltas(
            listOf(
                file("screenshots", "screenshot_screen.png", start = 1_000, end = 2_000, bytes = 40),
                file("screenshots", "screenshot_screen.jpg", start = 1_000, end = 1_500, bytes = 0),
            ),
            emptyList(),
            anchor = null,
        )
        assertEquals(1, deltas.size)
        assertEquals(INTAKE_SCREENSHOTS, deltas.single().sourceId)
        assertEquals(1L, deltas.single().add)
        assertEquals(2_000L, deltas.single().materialAt)
    }

    @Test
    fun aScreenshotIsLastTakenInWhenItIsKeptNotWhenItWasCaptured() {
        val capturedLongAgo = file("screenshots", "screenshot_screen.png", start = 1_000, end = 2_000, bytes = 40)
        val camera = file("camera", "camera.jpg", start = 0, end = 3_000, bytes = 20)
        val deltas = segmentIntakeDeltas(listOf(capturedLongAgo, camera), emptyList(), anchor = null, keptAtMs = 9_000_000L)
            .associateBy { it.sourceId }
        assertEquals(9_000_000L, deltas.getValue(INTAKE_SCREENSHOTS).materialAt)
        assertEquals(3_000L, deltas.getValue(INTAKE_CAMERA).materialAt)
    }

    @Test
    fun aFinishedSubSecondSpanOpensTheRowAndFilesFloorOnce() {
        val one = segmentIntakeDeltas(
            listOf(file("audio", "audio.m4a", start = 0, end = 400, bytes = 10)),
            emptyList(),
            anchor = null,
        )
        assertEquals(1, one.size)
        assertEquals(INTAKE_AUDIO, one[0].sourceId)
        assertEquals(1L, one[0].add)
        assertEquals(400L, one[0].materialAt)

        val underASecond = segmentIntakeDeltas(
            listOf(
                file("audio", "a.m4a", start = 0, end = 400, bytes = 10),
                file("audio", "b.m4a", start = 400, end = 800, bytes = 10),
            ),
            emptyList(),
            anchor = null,
        )
        assertEquals(1L, underASecond.single().add)

        val floored = segmentIntakeDeltas(
            listOf(
                file("screen", "a.mp4", start = 0, end = 1_900, bytes = 10),
                file("screen", "b.mp4", start = 1_900, end = 3_800, bytes = 10),
            ),
            emptyList(),
            anchor = null,
        )
        assertEquals(INTAKE_SCREEN, floored.single().sourceId)
        assertEquals(3L, floored.single().add)
        assertEquals(3_800L, floored.single().materialAt)

        val empty = segmentIntakeDeltas(
            listOf(file("audio", "empty.m4a", start = 5, end = 5, bytes = 10)),
            emptyList(),
            anchor = null,
        )
        assertTrue(empty.isEmpty())
    }

    @Test
    fun parserKeepsTheGoldenLineAndSkipsABadOne() {
        val text = """
            {"provider":"network","timestamp":1000,"lat":39.7392,"lon":-104.9903,"accuracy":12.5,"fixAge":60000}
            not json
            {"provider":"network","timestamp":2000,"lat":39.74,"lon":-104.99,"fixAge":1}
        """.trimIndent() + "\n"
        val fixes = parseLocationFixes(text)
        assertEquals(2, fixes.size)
        assertEquals(1000L, fixes[0].atEpochMs)
        assertEquals(39.7392, fixes[0].lat)
        assertEquals(-104.9903, fixes[0].lon)
        assertEquals(12.5, fixes[0].accuracyMeters)
        assertEquals(2000L, fixes[1].atEpochMs)
        assertNull(fixes[1].accuracyMeters)
    }

    @Test
    fun duplicateTimestampsInsideASegmentCountOnce() {
        val north = latitudeDegrees(1_000.0)
        val delta = locationDelta(
            listOf(
                fix(at = 1, lat = 0.0, accuracy = 5.0),
                fix(at = 1, lat = north, accuracy = 5.0),
                fix(at = 2, lat = north, accuracy = 5.0),
            ),
            anchor = null,
        )
        assertNear(1_000.0, delta?.add)
    }

    private fun fix(at: Long, lat: Double, accuracy: Double?) =
        IntakeFix(atEpochMs = at, lat = lat, lon = 0.0, accuracyMeters = accuracy)

    private fun file(sourceId: String, name: String, start: Long, end: Long, bytes: Long) = SegmentFileRow(
        segmentId = "seg",
        sourceId = sourceId,
        name = name,
        sha256 = name,
        byteSize = bytes,
        mediaType = "application/octet-stream",
        captureStartEpochMs = start,
        captureEndEpochMs = end,
    )

    private fun latitudeDegrees(metres: Double): Double = metres / (6_371_000.0 * Math.PI / 180.0)

    private fun assertNear(metres: Double, millimetres: Long?) {
        assertTrue(millimetres != null, "missing distance")
        assertTrue(abs(millimetres - metres * 1_000.0) <= 1_000.0, "expected $metres m, was $millimetres mm")
    }
}
