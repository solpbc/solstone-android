// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

/** Owner-facing surfaces of the post-pair device choice; one table for every form factor. */
enum class MigrationSurface {
    OFFER,
    PREPARING,
    PICKER,
    PICKER_EMPTY,
    CONFIRM,
    DECIDING,
    CHECKING,
    OFFLINE,
    UNSUPPORTED,
    STORAGE,
    LIST_UNAVAILABLE,
    TARGET_UNAVAILABLE,
    REFUSED,
    KEY_REFUSED,
}

data class MigrationCopy(val title: String, val body: String? = null, val action: String? = null)

fun migrationCopy(surface: MigrationSurface, selectedLabel: String? = null): MigrationCopy = when (surface) {
    MigrationSurface.OFFER -> MigrationCopy(
        "is this replacing one of your devices?",
        "you can keep both, or choose a device for this one to replace.",
    )
    MigrationSurface.PREPARING -> MigrationCopy(
        "getting this device ready",
        "anything waiting to send stays on this device until it's ready.",
    )
    MigrationSurface.PICKER -> MigrationCopy("choose a device")
    MigrationSurface.PICKER_EMPTY -> MigrationCopy("choose a device", "no other paired devices")
    MigrationSurface.CONFIRM -> MigrationCopy(
        selectedLabel?.takeIf(String::isNotBlank)?.let { "replace \"$it\"?" } ?: "replace the selected device?",
        "this device continues its name and history. the selected device will lose access to your journal.",
    )
    MigrationSurface.DECIDING -> MigrationCopy("saving your choice")
    MigrationSurface.CHECKING -> MigrationCopy(
        "checking your choice",
        "your journal hasn't confirmed the result yet.",
        "check again",
    )
    MigrationSurface.OFFLINE -> MigrationCopy(
        "can't reach your journal",
        "anything waiting to send stays on this device. try again when your journal is reachable.",
        "try again",
    )
    MigrationSurface.UNSUPPORTED -> MigrationCopy(
        "journal update needed",
        "your journal doesn't support this move yet. anything waiting to send stays on this device. update your journal, then try again.",
        "try again",
    )
    MigrationSurface.STORAGE -> MigrationCopy(
        "saved connection unavailable",
        "this device couldn't read its saved connection. anything waiting to send hasn't been removed.",
        "technical details",
    )
    MigrationSurface.LIST_UNAVAILABLE -> MigrationCopy(
        "devices unavailable",
        "couldn't load the devices in your journal. try again when your journal is reachable.",
        "try again",
    )
    MigrationSurface.TARGET_UNAVAILABLE -> MigrationCopy(
        "device no longer available",
        "that device is no longer listed in your journal. choose another device, or keep both.",
        "choose a device",
    )
    MigrationSurface.REFUSED -> MigrationCopy(
        "choice needs attention",
        "your journal couldn't apply this choice. your current connection still works.",
        "technical details",
    )
    MigrationSurface.KEY_REFUSED -> MigrationCopy(
        "pair again",
        "this device couldn't open its saved connection to your journal. anything waiting to send is still here. pair again to reconnect.",
        "pair again",
    )
}
