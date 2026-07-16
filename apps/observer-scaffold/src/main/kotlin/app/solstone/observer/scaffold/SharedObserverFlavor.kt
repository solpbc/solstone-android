// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.OpportunisticSync
import app.solstone.platform.power.GuidanceAction
import app.solstone.platform.power.GuidanceLaunchResult

data class SharedObserverFlavor(
    val controller: HarnessController,
    val heartbeatControl: HeartbeatControl? = null,
    val syncControl: SyncControl? = null,
    val opportunisticSync: OpportunisticSync? = null,
    val exemptionVerified: () -> Boolean,
    val batteryGuidance: GuidanceAction,
    val launchBatteryGuidance: () -> GuidanceLaunchResult,
    val isUsableNetworkPresent: () -> Boolean,
)

interface HeartbeatControl {
    fun setFresh(fresh: Boolean)
}

interface SyncControl {
    val enqueuePeriodicCalls: Int
    val enqueueNowCalls: Int
}
