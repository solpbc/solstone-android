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
import android.os.CancellationSignal
import androidx.core.location.LocationManagerCompat
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
        val signal = CancellationSignal()
        val finished = AtomicBoolean(false)
        return try {
            LocationManagerCompat.getCurrentLocation(
                manager,
                provider,
                signal,
                DIRECT_EXECUTOR,
            ) { location ->
                if (finished.compareAndSet(false, true)) {
                    onResult(location?.toFix(System.currentTimeMillis()))
                }
            }
            FreshFixCancel {
                finished.set(true)
                signal.cancel()
            }
        } catch (_: SecurityException) {
            FreshFixCancel { }
        } catch (_: IllegalArgumentException) {
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
    }
}
