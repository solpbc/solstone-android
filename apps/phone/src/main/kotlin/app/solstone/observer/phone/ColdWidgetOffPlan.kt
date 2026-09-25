// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.model.ReasonCode
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.WishSaveOutcome
import app.solstone.observer.harness.WishStoreState
import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.fgs.MaskNarrowResult
import app.solstone.platform.fgs.captureForegroundTypeForSourceId
import app.solstone.platform.fgs.effectiveCapturePlan

data class AudioOffCommit(
    val outcome: WishSaveOutcome,
    val wishesNow: Map<String, SourceWish>,
)

fun audioOffWishes(
    store: WishStoreState,
    registrationIds: List<String>,
): Map<String, SourceWish> = when (store) {
    is WishStoreState.Unreadable -> {
        val map = LinkedHashMap<String, SourceWish>()
        registrationIds.forEach { id ->
            map[id] = if (id == "audio") SourceWish.Off else SourceWish.On
        }
        if ("audio" !in map) {
            map["audio"] = SourceWish.Off
        }
        map
    }
    is WishStoreState.Loaded -> {
        val map = LinkedHashMap(store.wishes)
        map["audio"] = SourceWish.Off
        map
    }
    WishStoreState.Absent -> {
        mapOf("audio" to SourceWish.Off)
    }
}

fun performAudioOff(
    registrationIds: List<String>,
    readStore: () -> WishStoreState,
    saveResolved: (Map<String, SourceWish>) -> WishSaveOutcome,
    commitThroughRegistry: (() -> AudioOffCommit)?,
    microphoneGranted: Boolean,
    cameraGranted: Boolean,
    locationGranted: Boolean,
    declared: Set<CaptureForegroundType>,
    serviceHeld: Boolean,
    narrowRunningMask: (Set<CaptureForegroundType>) -> MaskNarrowResult,
    stop: (endSession: Boolean) -> Unit,
    recordOwnerStopped: () -> Boolean,
    commitDesiredOff: () -> Boolean,
    publishNotice: (ReasonCode) -> Unit,
    clearNotice: () -> Unit,
) {
    val (outcome, wishesNow) = if (commitThroughRegistry != null) {
        val commit = commitThroughRegistry()
        commit.outcome to commit.wishesNow
    } else {
        val read = readStore()
        val resolved = audioOffWishes(read, registrationIds)
        val saveOutcome = saveResolved(resolved)
        val now = if (saveOutcome is WishSaveOutcome.Committed) {
            resolved
        } else {
            when (read) {
                is WishStoreState.Loaded -> read.wishes
                is WishStoreState.Unreadable -> registrationIds.associateWith { SourceWish.On }
                WishStoreState.Absent -> emptyMap()
            }
        }
        saveOutcome to now
    }

    if (outcome !is WishSaveOutcome.Committed) {
        val stoppedOk = recordOwnerStopped()
        val desiredOk = commitDesiredOff()
        stop(true)
        if (!stoppedOk || !desiredOk) {
            publishNotice(ReasonCode.AUDIO_CHOICE_NOT_SAVED_STOP_NOT_DURABLE)
        } else {
            publishNotice(ReasonCode.AUDIO_CHOICE_NOT_SAVED)
        }
        return
    }

    val wishedOn = wishesNow
        .filter { it.value == SourceWish.On }
        .mapNotNull { captureForegroundTypeForSourceId(it.key) }
        .toSet()

    val plan = effectiveCapturePlan(
        microphoneGranted = microphoneGranted,
        cameraGranted = cameraGranted,
        locationGranted = locationGranted,
        declared = declared,
        wishedOn = wishedOn,
        liveHeld = null,
    )

    if (plan.endSession) {
        val stoppedOk = recordOwnerStopped()
        val desiredOk = commitDesiredOff()
        stop(true)
        if (!stoppedOk || !desiredOk) {
            publishNotice(ReasonCode.AUDIO_CHOICE_SAVED_STOP_NOT_DURABLE)
        } else {
            clearNotice()
        }
    } else {
        if (!serviceHeld) {
            clearNotice()
        } else {
            val maskResult = narrowRunningMask(plan.types)
            if (maskResult is MaskNarrowResult.Applied) {
                clearNotice()
            } else {
                stop(false)
                publishNotice(ReasonCode.INTAKE_STOPPED_UNEXPECTEDLY)
            }
        }
    }
}
