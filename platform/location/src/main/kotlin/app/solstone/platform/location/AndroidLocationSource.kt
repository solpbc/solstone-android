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
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class AndroidLocationSource(private val context: Context) : LocationSource {
    private val manager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    override fun lastFix(nowEpochMs: Long): LocationFix? {
        if (!hasPermission()) return null
        val location = lastKnownProviders()
            .asSequence()
            .filter { provider -> isEnabled(provider) }
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

    @SuppressLint("MissingPermission")
    override fun requestFreshFix(onResult: (LocationFix?) -> Unit): FreshFixCancel {
        if (!hasPermission()) return FreshFixCancel { }
        val provider = freshFixProvider() ?: return FreshFixCancel { }
        val finished = AtomicBoolean(false)
        // Measured on a physical device: getCurrentLocation returns whatever the platform already
        // holds, so with the app stopped entirely the fused fix still refreshed on the system's own
        // ten-minute cadence, unchanged. Asking for a location is not the same as causing one. A
        // bounded single-update request is what actually makes the provider produce a new fix; it
        // is not a continuous subscription because it delivers at most one and expires on its own.
        val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                if (finished.compareAndSet(false, true)) {
                    runCatching { LocationManagerCompat.removeUpdates(manager, this) }
                    onResult(location.toFix(System.currentTimeMillis()))
                }
            }
        }
        val request = LocationRequestCompat.Builder(0L)
            .setMaxUpdates(1)
            .setDurationMillis(FRESH_FIX_TIMEOUT_MS)
            .setQuality(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
            .build()
        return try {
            LocationManagerCompat.requestLocationUpdates(manager, provider, request, DIRECT_EXECUTOR, listener)
            FreshFixCancel {
                if (finished.compareAndSet(false, true)) {
                    runCatching { LocationManagerCompat.removeUpdates(manager, listener) }
                }
            }
        } catch (_: SecurityException) {
            FreshFixCancel { }
        }
    }

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasAnyProvider(): Boolean =
        lastKnownProviders().any { provider -> isEnabled(provider) }

    // Coarse-legal providers that can produce a new fix. PASSIVE does not generate one; GPS is precise.
    private fun freshFixProvider(): String? {
        if (Build.VERSION.SDK_INT >= 31 && isEnabled(LocationManager.FUSED_PROVIDER)) {
            return LocationManager.FUSED_PROVIDER
        }
        if (isEnabled(LocationManager.NETWORK_PROVIDER)) {
            return LocationManager.NETWORK_PROVIDER
        }
        return null
    }

    private fun lastKnownProviders(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.PASSIVE_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }

    private fun isEnabled(provider: String): Boolean =
        runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)

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

    private companion object {
        val DIRECT_EXECUTOR = Executor { command -> command.run() }
        const val FRESH_FIX_TIMEOUT_MS = 60_000L
    }
}
