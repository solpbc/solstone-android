// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.power

import android.content.Context
import android.os.PowerManager

fun interface BatteryExemptionStatus {
    fun isIgnoringBatteryOptimizations(): Boolean
}

class AndroidBatteryExemptionStatus(private val context: Context) : BatteryExemptionStatus {
    override fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
}
