// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.location

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build

class AndroidLocationSource(private val context: Context) : LocationSource {
    private val manager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    override fun lastFix(nowEpochMs: Long): LocationFix? {
        if (!hasPermission()) return null
        val location = listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .asSequence()
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?: return null
        return location.toFix(nowEpochMs)
    }

    override fun noFixReason(): NoFixReason =
        when {
            !hasPermission() -> NoFixReason.PERMISSION
            !hasAnyProvider() -> NoFixReason.PROVIDER_DISABLED
            else -> NoFixReason.NO_FIX
        }

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasAnyProvider(): Boolean =
        listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .any { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }

    private fun Location.toFix(nowEpochMs: Long): LocationFix =
        LocationFix(
            provider = provider ?: "unknown",
            timestampEpochMs = time,
            lat = latitude,
            lon = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else Double.NaN,
            fixAgeMs = if (Build.VERSION.SDK_INT >= 17) {
                maxOf(0L, android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000L - elapsedRealtimeNanos / 1_000_000L)
            } else {
                maxOf(0L, nowEpochMs - time)
            },
        )
}
