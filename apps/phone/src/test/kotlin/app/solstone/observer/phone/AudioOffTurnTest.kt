// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.model.ReasonCode
import app.solstone.observer.harness.FileSourceWishStore
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.WishSaveOutcome
import app.solstone.observer.harness.WishStoreState
import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.fgs.MaskNarrowResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioOffTurnTest {
    @Test
    fun coldUnreadableWithRemainingSourceNarrowsMask() {
        var savedWishes: Map<String, SourceWish>? = null
        var narrowedTypes: Set<CaptureForegroundType>? = null
        var stoppedSession: Boolean? = null
        var recordedStopped = false
        var committedDesiredOff = false
        var publishedNotice: ReasonCode? = null
        var noticeCleared = false

        performAudioOff(
            registrationIds = listOf("audio", "location"),
            readStore = { WishStoreState.Unreadable },
            saveResolved = {
                savedWishes = it
                WishSaveOutcome.Committed
            },
            commitThroughRegistry = null,
            microphoneGranted = true,
            cameraGranted = false,
            locationGranted = true,
            declared = setOf(CaptureForegroundType.MICROPHONE, CaptureForegroundType.LOCATION),
            serviceHeld = true,
            narrowRunningMask = {
                narrowedTypes = it
                MaskNarrowResult.Applied
            },
            stop = { endSession -> stoppedSession = endSession },
            recordOwnerStopped = {
                recordedStopped = true
                true
            },
            commitDesiredOff = {
                committedDesiredOff = true
                true
            },
            publishNotice = { publishedNotice = it },
            clearNotice = { noticeCleared = true },
        )

        assertEquals(
            mapOf("audio" to SourceWish.Off, "location" to SourceWish.On),
            savedWishes,
        )
        assertEquals(setOf(CaptureForegroundType.LOCATION), narrowedTypes)
        assertEquals(null, stoppedSession)
        assertFalse(recordedStopped, "markers must not be recorded when session continues")
        assertFalse(committedDesiredOff, "desired off must not be committed when session continues")
        assertEquals(null, publishedNotice)
        assertTrue(noticeCleared)
    }

    @Test
    fun coldUnreadableWithRealFileStoreAndMarkers() {
        val dir = Files.createTempDirectory("cold-audio-off-real-file").toFile()
        val file = dir.resolve("source-wishes")
        val store = FileSourceWishStore(file)

        var stoppedSession: Boolean? = null
        var recordedStopped = false
        var committedDesiredOff = false
        var narrowCalls = 0
        var publishedNotice: ReasonCode? = null
        var noticeCleared = false

        performAudioOff(
            registrationIds = listOf("audio", "location", "camera"),
            readStore = { WishStoreState.Unreadable },
            saveResolved = { store.saveAll(it) },
            commitThroughRegistry = null,
            microphoneGranted = true,
            cameraGranted = false,
            locationGranted = false,
            declared = setOf(
                CaptureForegroundType.MICROPHONE,
                CaptureForegroundType.LOCATION,
                CaptureForegroundType.CAMERA,
            ),
            serviceHeld = true,
            narrowRunningMask = {
                narrowCalls += 1
                MaskNarrowResult.Applied
            },
            stop = { endSession -> stoppedSession = endSession },
            recordOwnerStopped = {
                recordedStopped = true
                true
            },
            commitDesiredOff = {
                committedDesiredOff = true
                true
            },
            publishNotice = { publishedNotice = it },
            clearNotice = { noticeCleared = true },
        )

        assertEquals("audio\tOff\nlocation\tOn\ncamera\tOn\n", file.readText())
        assertEquals(true, stoppedSession)
        assertTrue(recordedStopped)
        assertTrue(committedDesiredOff)
        assertEquals(0, narrowCalls, "narrowRunningMask must not be called when ending session")
        assertEquals(null, publishedNotice)
        assertTrue(noticeCleared)
    }

    @Test
    fun coldUnreadableLocationGrantedSaveOriginalIntact() {
        var narrowCalls = 0
        var stoppedSession: Boolean? = null
        var recordedStopped = false
        var committedDesiredOff = false
        var publishedNotice: ReasonCode? = null

        performAudioOff(
            registrationIds = listOf("audio", "location"),
            readStore = { WishStoreState.Unreadable },
            saveResolved = { WishSaveOutcome.OriginalIntact },
            commitThroughRegistry = null,
            microphoneGranted = true,
            cameraGranted = false,
            locationGranted = true,
            declared = setOf(CaptureForegroundType.MICROPHONE, CaptureForegroundType.LOCATION),
            serviceHeld = true,
            narrowRunningMask = {
                narrowCalls += 1
                MaskNarrowResult.Applied
            },
            stop = { endSession -> stoppedSession = endSession },
            recordOwnerStopped = {
                recordedStopped = true
                true
            },
            commitDesiredOff = {
                committedDesiredOff = true
                true
            },
            publishNotice = { publishedNotice = it },
            clearNotice = {},
        )

        assertEquals(0, narrowCalls, "narrowRunningMask must not be called when save fails")
        assertTrue(recordedStopped)
        assertTrue(committedDesiredOff)
        assertEquals(true, stoppedSession)
        assertEquals(ReasonCode.AUDIO_CHOICE_NOT_SAVED, publishedNotice)
    }

    @Test
    fun coldUnreadableLocationGrantedSaveUncertain() {
        var narrowCalls = 0
        var stoppedSession: Boolean? = null
        var recordedStopped = false
        var committedDesiredOff = false
        var publishedNotice: ReasonCode? = null

        performAudioOff(
            registrationIds = listOf("audio", "location"),
            readStore = { WishStoreState.Unreadable },
            saveResolved = { WishSaveOutcome.Uncertain },
            commitThroughRegistry = null,
            microphoneGranted = true,
            cameraGranted = false,
            locationGranted = true,
            declared = setOf(CaptureForegroundType.MICROPHONE, CaptureForegroundType.LOCATION),
            serviceHeld = true,
            narrowRunningMask = {
                narrowCalls += 1
                MaskNarrowResult.Applied
            },
            stop = { endSession -> stoppedSession = endSession },
            recordOwnerStopped = {
                recordedStopped = true
                true
            },
            commitDesiredOff = {
                committedDesiredOff = true
                true
            },
            publishNotice = { publishedNotice = it },
            clearNotice = {},
        )

        assertEquals(0, narrowCalls, "narrowRunningMask must not be called when save is uncertain")
        assertTrue(recordedStopped)
        assertTrue(committedDesiredOff)
        assertEquals(true, stoppedSession)
        assertEquals(ReasonCode.AUDIO_CHOICE_NOT_SAVED, publishedNotice)
    }

    @Test
    fun commitThroughRegistryOriginalIntactLeavesSaveResolvedUncalled() {
        var saveResolvedCalls = 0
        var narrowCalls = 0
        var stoppedSession: Boolean? = null
        var publishedNotice: ReasonCode? = null

        performAudioOff(
            registrationIds = listOf("audio", "location"),
            readStore = { WishStoreState.Loaded(mapOf("audio" to SourceWish.On, "location" to SourceWish.On)) },
            saveResolved = {
                saveResolvedCalls += 1
                WishSaveOutcome.Committed
            },
            commitThroughRegistry = {
                AudioOffCommit(
                    outcome = WishSaveOutcome.OriginalIntact,
                    wishesNow = mapOf("audio" to SourceWish.On, "location" to SourceWish.On),
                )
            },
            microphoneGranted = true,
            cameraGranted = false,
            locationGranted = true,
            declared = setOf(CaptureForegroundType.MICROPHONE, CaptureForegroundType.LOCATION),
            serviceHeld = true,
            narrowRunningMask = {
                narrowCalls += 1
                MaskNarrowResult.Applied
            },
            stop = { endSession -> stoppedSession = endSession },
            recordOwnerStopped = { true },
            commitDesiredOff = { true },
            publishNotice = { publishedNotice = it },
            clearNotice = {},
        )

        assertEquals(0, saveResolvedCalls, "saveResolved must not be called when commitThroughRegistry is provided")
        assertEquals(0, narrowCalls, "narrowRunningMask must not be called on failure")
        assertEquals(true, stoppedSession)
        assertEquals(ReasonCode.AUDIO_CHOICE_NOT_SAVED, publishedNotice)
    }

    @Test
    fun saveCommitsLocationGrantedNarrowApplyFailed() {
        var stoppedSession: Boolean? = null
        var recordedStopped = false
        var committedDesiredOff = false
        var publishedNotice: ReasonCode? = null

        performAudioOff(
            registrationIds = listOf("audio", "location"),
            readStore = { WishStoreState.Loaded(mapOf("audio" to SourceWish.On, "location" to SourceWish.On)) },
            saveResolved = { WishSaveOutcome.Committed },
            commitThroughRegistry = null,
            microphoneGranted = true,
            cameraGranted = false,
            locationGranted = true,
            declared = setOf(CaptureForegroundType.MICROPHONE, CaptureForegroundType.LOCATION),
            serviceHeld = true,
            narrowRunningMask = { MaskNarrowResult.ApplyFailed },
            stop = { endSession -> stoppedSession = endSession },
            recordOwnerStopped = {
                recordedStopped = true
                true
            },
            commitDesiredOff = {
                committedDesiredOff = true
                true
            },
            publishNotice = { publishedNotice = it },
            clearNotice = {},
        )

        assertEquals(false, stoppedSession, "stop(false) must be called when narrow fails")
        assertFalse(recordedStopped, "owner stopped must not be recorded when narrow fails")
        assertFalse(committedDesiredOff, "desired off must not be committed when narrow fails")
        assertEquals(ReasonCode.INTAKE_STOPPED_UNEXPECTEDLY, publishedNotice)
    }

    @Test
    fun saveFailurePublishesAudioChoiceNotSavedAndStopsService() {
        var stoppedSession: Boolean? = null
        var publishedNotice: ReasonCode? = null

        performAudioOff(
            registrationIds = listOf("audio"),
            readStore = { WishStoreState.Loaded(mapOf("audio" to SourceWish.On)) },
            saveResolved = { WishSaveOutcome.OriginalIntact },
            commitThroughRegistry = null,
            microphoneGranted = true,
            cameraGranted = false,
            locationGranted = false,
            declared = setOf(CaptureForegroundType.MICROPHONE),
            serviceHeld = true,
            narrowRunningMask = { MaskNarrowResult.Applied },
            stop = { endSession -> stoppedSession = endSession },
            recordOwnerStopped = { true },
            commitDesiredOff = { true },
            publishNotice = { publishedNotice = it },
            clearNotice = {},
        )

        assertEquals(ReasonCode.AUDIO_CHOICE_NOT_SAVED, publishedNotice)
        assertEquals(true, stoppedSession)
    }

    @Test
    fun markerCommitFailurePublishesStopNotDurable() {
        var publishedNotice: ReasonCode? = null

        performAudioOff(
            registrationIds = listOf("audio"),
            readStore = { WishStoreState.Loaded(mapOf("audio" to SourceWish.On)) },
            saveResolved = { WishSaveOutcome.Committed },
            commitThroughRegistry = null,
            microphoneGranted = true,
            cameraGranted = false,
            locationGranted = false,
            declared = setOf(CaptureForegroundType.MICROPHONE),
            serviceHeld = true,
            narrowRunningMask = { MaskNarrowResult.Applied },
            stop = { },
            recordOwnerStopped = { false }, // Marker failed
            commitDesiredOff = { true },
            publishNotice = { publishedNotice = it },
            clearNotice = {},
        )

        assertEquals(ReasonCode.AUDIO_CHOICE_SAVED_STOP_NOT_DURABLE, publishedNotice)
    }

    @Test
    fun bothSaveAndMarkerFailurePublishesNotSavedStopNotDurable() {
        var publishedNotice: ReasonCode? = null

        performAudioOff(
            registrationIds = listOf("audio"),
            readStore = { WishStoreState.Loaded(mapOf("audio" to SourceWish.On)) },
            saveResolved = { WishSaveOutcome.OriginalIntact },
            commitThroughRegistry = null,
            microphoneGranted = true,
            cameraGranted = false,
            locationGranted = false,
            declared = setOf(CaptureForegroundType.MICROPHONE),
            serviceHeld = true,
            narrowRunningMask = { MaskNarrowResult.Applied },
            stop = { },
            recordOwnerStopped = { false },
            commitDesiredOff = { false },
            publishNotice = { publishedNotice = it },
            clearNotice = {},
        )

        assertEquals(ReasonCode.AUDIO_CHOICE_NOT_SAVED_STOP_NOT_DURABLE, publishedNotice)
    }

    @Test
    fun narrowRequestFailurePublishesIntakeStoppedUnexpectedly() {
        var stoppedSession: Boolean? = null
        var publishedNotice: ReasonCode? = null

        performAudioOff(
            registrationIds = listOf("audio", "location"),
            readStore = { WishStoreState.Loaded(mapOf("audio" to SourceWish.On, "location" to SourceWish.On)) },
            saveResolved = { WishSaveOutcome.Committed },
            commitThroughRegistry = null,
            microphoneGranted = true,
            cameraGranted = false,
            locationGranted = true,
            declared = setOf(CaptureForegroundType.MICROPHONE, CaptureForegroundType.LOCATION),
            serviceHeld = true,
            narrowRunningMask = { MaskNarrowResult.RequestFailed },
            stop = { endSession -> stoppedSession = endSession },
            recordOwnerStopped = { true },
            commitDesiredOff = { true },
            publishNotice = { publishedNotice = it },
            clearNotice = {},
        )

        assertEquals(ReasonCode.INTAKE_STOPPED_UNEXPECTEDLY, publishedNotice)
        assertEquals(false, stoppedSession)
    }
}
