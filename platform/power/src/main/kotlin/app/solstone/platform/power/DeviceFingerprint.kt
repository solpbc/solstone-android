// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.power

import android.os.Build

data class DeviceFingerprint(
    val manufacturer: String,
    val brand: String,
    val model: String,
) {
    fun normalized(): DeviceFingerprint =
        copy(
            manufacturer = manufacturer.trim().lowercase(),
            brand = brand.trim().lowercase(),
            model = model.trim().lowercase(),
        )
}

fun interface DeviceFingerprintProvider {
    fun fingerprint(): DeviceFingerprint
}

class AndroidDeviceFingerprintProvider : DeviceFingerprintProvider {
    override fun fingerprint(): DeviceFingerprint =
        DeviceFingerprint(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
        )
}
