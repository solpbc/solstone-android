// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.metadata

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.solstone.core.metadata.BatterySnapshot
import app.solstone.core.metadata.BatterySource
import app.solstone.core.metadata.BatteryStatus

class AndroidBatterySource(private val context: Context) : BatterySource {
    override fun snapshot(): BatterySnapshot? {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        return BatterySnapshot(
            level = intent.batteryLevel(),
            status = intent.batteryStatus(),
            tempC = intent.batteryTemperatureC(),
        )
    }

    private fun Intent.batteryLevel(): Int? =
        runCatching {
            val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) null else (level * 100) / scale
        }.getOrNull()

    private fun Intent.batteryStatus(): BatteryStatus? =
        runCatching {
            when (getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> BatteryStatus.CHARGING
                BatteryManager.BATTERY_STATUS_FULL -> BatteryStatus.FULL
                BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryStatus.DISCHARGING
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryStatus.NOT_CHARGING
                -1 -> null
                else -> BatteryStatus.UNKNOWN
            }
        }.getOrNull()

    private fun Intent.batteryTemperatureC(): Double? =
        runCatching {
            val tenthsC = getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (tenthsC < 0) null else tenthsC / 10.0
        }.getOrNull()
}
