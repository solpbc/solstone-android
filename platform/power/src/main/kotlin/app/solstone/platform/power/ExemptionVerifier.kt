// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.power

class ExemptionVerifier(
    private val batteryExemptionStatus: BatteryExemptionStatus,
    private val autostartConfirmationStore: AutostartConfirmationStore,
    private val autostartRequired: Boolean = true,
) {
    fun isExemptionVerified(): Boolean =
        batteryExemptionStatus.isIgnoringBatteryOptimizations() &&
            (!autostartRequired || autostartConfirmationStore.isAutostartConfirmed())
}
