// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.WishStoreState
import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.fgs.effectiveCapturePlan

data class ColdAudioOffPlan(
    val wishesToWrite: Map<String, SourceWish>?,
    val recordOwnerStopped: Boolean,
    val commitDesiredOff: Boolean,
    val stopService: Boolean,
    val refreshRunningMask: Boolean,
)

fun planColdAudioOff(
    store: WishStoreState,
    microphoneGranted: Boolean,
    cameraGranted: Boolean,
    locationGranted: Boolean,
    declared: Set<CaptureForegroundType>,
    serviceHeld: Boolean,
): ColdAudioOffPlan {
    if (store is WishStoreState.Unreadable) {
        return ColdAudioOffPlan(
            wishesToWrite = null,
            recordOwnerStopped = false,
            commitDesiredOff = false,
            stopService = false,
            refreshRunningMask = false,
        )
    }
    val existing = when (store) {
        is WishStoreState.Loaded -> store.wishes
        WishStoreState.Absent -> emptyMap()
        is WishStoreState.Unreadable -> emptyMap()
    }
    val merged = LinkedHashMap(existing)
    merged["audio"] = SourceWish.Off

    val onTypes = linkedSetOf<CaptureForegroundType>()
    if (merged["audio"] == SourceWish.On) onTypes.add(CaptureForegroundType.MICROPHONE)
    if (merged["location"] == SourceWish.On) onTypes.add(CaptureForegroundType.LOCATION)
    if (merged["camera"] == SourceWish.On) onTypes.add(CaptureForegroundType.CAMERA)

    val plan = effectiveCapturePlan(
        microphoneGranted = microphoneGranted,
        cameraGranted = cameraGranted,
        locationGranted = locationGranted,
        declared = declared,
        wishedOn = onTypes,
        liveHeld = null,
    )

    return if (plan.endSession) {
        ColdAudioOffPlan(
            wishesToWrite = merged,
            recordOwnerStopped = true,
            commitDesiredOff = true,
            stopService = true,
            refreshRunningMask = false,
        )
    } else {
        ColdAudioOffPlan(
            wishesToWrite = merged,
            recordOwnerStopped = false,
            commitDesiredOff = false,
            stopService = false,
            refreshRunningMask = serviceHeld,
        )
    }
}
