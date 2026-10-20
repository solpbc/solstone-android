// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportIntakeRulesTest {
    @Test
    fun theJournalsTypesAreTakenAndVideoIsNot() {
        listOf("application/pdf", "text/plain", "audio/mpeg", "audio/mp4", "image/png", "image/jpeg", "image/webp", "image/gif").forEach {
            assertEquals(IntakeDecision.Accept(it), intakeDecision("content", it, canConvertHeic = true))
        }
        listOf("video/mp4", "image/avif", "image/x-adobe-dng", "image/tiff", "application/vnd.openxmlformats-officedocument.wordprocessingml.document").forEach {
            assertEquals(IntakeDecision.Refuse(IntakeRefusal.UNSUPPORTED_TYPE), intakeDecision("content", it, canConvertHeic = true))
        }
        assertEquals(IntakeDecision.Refuse(IntakeRefusal.UNSUPPORTED_TYPE), intakeDecision("content", null, canConvertHeic = true))
    }

    @Test
    fun aTypeWithParametersOrCapitalsIsStillRecognized() {
        assertEquals(IntakeDecision.Accept("text/plain"), intakeDecision("content", "Text/Plain; charset=utf-8", canConvertHeic = true))
    }

    @Test
    fun heicIsConvertedWhereThePhoneCanAndRefusedWhereItCannot() {
        assertEquals(IntakeDecision.ConvertToJpeg, intakeDecision("content", "image/heic", canConvertHeic = true))
        assertEquals(IntakeDecision.Refuse(IntakeRefusal.CANNOT_CONVERT), intakeDecision("content", "image/heif", canConvertHeic = false))
    }

    @Test
    fun onlyContentUrisAreTaken() {
        assertEquals(IntakeDecision.Refuse(IntakeRefusal.NOT_SHAREABLE), intakeDecision("file", "image/png", canConvertHeic = true))
        assertEquals(IntakeDecision.Refuse(IntakeRefusal.NOT_SHAREABLE), intakeDecision(null, "image/png", canConvertHeic = true))
    }

    @Test
    fun aNameWithoutAnExtensionGetsOneFromItsType() {
        assertEquals("notes.pdf", intakeName("notes.pdf", "application/pdf"))
        assertEquals("1000012345.jpg", intakeName("1000012345", "image/jpeg"))
        assertEquals("file.txt", intakeName(null, "text/plain"))
        assertEquals("file.m4a", intakeName("  ", "audio/mp4"))
        assertEquals("voice.ogg", intakeName("voice", "audio/ogg"))
        assertEquals("song.mp3", intakeName("/storage/music/song.mp3", "audio/mpeg"))
        assertEquals(".hidden.png", intakeName(".hidden", "image/png").let { it }, "a leading dot is not an extension")
    }

    @Test
    fun aConvertedPhotoIsSentAsJpeg() {
        assertEquals("IMG_0001.jpg", intakeName("IMG_0001.HEIC", "image/heic", convertedToJpeg = true))
        assertEquals("file.jpg", intakeName(null, "image/heic", convertedToJpeg = true))
    }

    @Test
    fun theCapIsExactAndTheFloorProtectsCapture() {
        val cap = 1_000L
        val floor = 100L
        assertNull(intakeMayCopy(copiedSoFar = 900, nextChunk = 100, usableSpace = 10_000, maxBytes = cap, minFree = floor))
        assertEquals(IntakeRefusal.TOO_LARGE, intakeMayCopy(copiedSoFar = 1_000, nextChunk = 1, usableSpace = 10_000, maxBytes = cap, minFree = floor))
        assertEquals(IntakeRefusal.NO_SPACE, intakeMayCopy(copiedSoFar = 0, nextChunk = 50, usableSpace = 140, maxBytes = cap, minFree = floor))
        assertEquals(IntakeRefusal.TOO_LARGE, intakeMayStart(reportedSize = 1_001, usableSpace = 10_000, maxBytes = cap, minFree = floor))
        assertNull(intakeMayStart(reportedSize = null, usableSpace = 10_000, maxBytes = cap, minFree = floor))
        assertEquals(IntakeRefusal.NO_SPACE, intakeMayStart(reportedSize = 950, usableSpace = 1_000, maxBytes = cap, minFree = floor))
    }

    @Test
    fun theProductionCapIsTheJournalsAndTheFloorAtLeastCapturesScreenFloor() {
        assertEquals(4L * 1024 * 1024 * 1024, INTAKE_MAX_BYTES)
        kotlin.test.assertTrue(INTAKE_MIN_FREE_BYTES >= 256L * 1024 * 1024)
    }
}
