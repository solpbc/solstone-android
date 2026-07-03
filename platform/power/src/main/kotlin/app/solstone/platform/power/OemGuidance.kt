// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.power

import android.content.Intent
import android.net.Uri
import android.provider.Settings

data class GuidanceAction(
    val intentAction: String,
    val intentData: String?,
    val instructionText: String,
) {
    fun toIntent(): Intent =
        Intent(intentAction).also { intent ->
            if (intentData != null) intent.data = Uri.parse(intentData)
        }
}

data class OemGuidance(
    val id: String,
    val batteryExemption: GuidanceAction,
    val autostart: GuidanceAction,
    val autostartAvailable: Boolean = true,
)

object OemGuidanceCatalog {
    fun select(fingerprint: DeviceFingerprint): OemGuidance {
        val normalized = fingerprint.normalized()
        return when {
            // ASSUMED/UNVERIFIED Build strings: docs/device-matrix.md confirms RV203 hardware only.
            normalized.manufacturer.contains("rokid") ||
                normalized.brand.contains("rokid") ||
                normalized.model.contains("rv203") -> rokid
            normalized.manufacturer.contains("rogbid") ||
                normalized.brand.contains("rogbid") ||
                normalized.model.contains("model x") -> rogbid
            normalized.manufacturer.contains("samsung") ||
                normalized.brand.contains("samsung") -> samsung
            else -> generic
        }
    }

    val generic: OemGuidance = OemGuidance(
        id = "generic",
        batteryExemption = GuidanceAction(
            intentAction = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            intentData = null,
            instructionText = "Allow sol to stay active in the background.",
        ),
        autostart = GuidanceAction(
            intentAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            intentData = packageUri,
            instructionText = "Open app settings and enable autostart or auto-launch if your device offers it.",
        ),
    )

    val rokid: OemGuidance = OemGuidance(
        id = "rokid",
        batteryExemption = GuidanceAction(
            intentAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            intentData = packageUri,
            instructionText = "Open app settings and allow sol to keep running where the OS permits it. Battery and background survival are best-effort.",
        ),
        autostart = GuidanceAction(
            intentAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            intentData = packageUri,
            instructionText = "No reliable normal-app or ADB autostart path is known, and sol cannot toggle Wi-Fi as a normal app. After reboot, start sol explicitly if the OS did not preserve it.",
        ),
        autostartAvailable = false,
    )

    val rogbid: OemGuidance = OemGuidance(
        id = "rogbid",
        batteryExemption = GuidanceAction(
            intentAction = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            intentData = null,
            instructionText = "Open battery settings and allow sol to stay active after the screen turns off.",
        ),
        autostart = GuidanceAction(
            intentAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            intentData = packageUri,
            instructionText = "Open Rogbid app management and enable auto-launch for sol.",
        ),
    )

    val samsung: OemGuidance = OemGuidance(
        id = "samsung",
        batteryExemption = GuidanceAction(
            intentAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            intentData = packageUri,
            instructionText = "Open Battery, set sol to unrestricted, and leave background use allowed.",
        ),
        autostart = GuidanceAction(
            intentAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            intentData = packageUri,
            instructionText = "Open Samsung app settings and leave auto-run behavior allowed for sol.",
        ),
    )

    private const val packageUri: String = "package:"
}
