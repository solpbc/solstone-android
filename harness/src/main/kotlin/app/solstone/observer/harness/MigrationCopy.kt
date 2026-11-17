// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.i18n.phoneText

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
        phoneText("android.migration.replacing.one.devices"),
        phoneText("android.migration.can.keep.both.or.choose"),
    )
    MigrationSurface.PREPARING -> MigrationCopy(
        phoneText("android.migration.getting.device.ready"),
        phoneText("android.migration.anything.waiting.send.stays.device"),
    )
    MigrationSurface.PICKER -> MigrationCopy(phoneText("android.migration.choose.device"))
    MigrationSurface.PICKER_EMPTY -> MigrationCopy(phoneText("android.migration.choose.device"), phoneText("android.migration.no.other.paired.devices"))
    MigrationSurface.CONFIRM -> MigrationCopy(
        selectedLabel?.takeIf(String::isNotBlank)?.let { phoneText("android.migration.replace" , "it" to (it)) } ?: phoneText("android.migration.replace.selected.device"),
        phoneText("android.migration.device.continues.its.name.history"),
    )
    MigrationSurface.DECIDING -> MigrationCopy(phoneText("android.migration.saving.choice"))
    MigrationSurface.CHECKING -> MigrationCopy(
        phoneText("android.migration.checking.choice"),
        phoneText("android.migration.journal.hasn.t.confirmed.result"),
        phoneText("android.migration.check.again"),
    )
    MigrationSurface.OFFLINE -> MigrationCopy(
        phoneText("android.migration.can.t.reach.journal"),
        phoneText("android.migration.anything.waiting.send.stays.device.n2"),
        phoneText("android.migration.try.again"),
    )
    MigrationSurface.UNSUPPORTED -> MigrationCopy(
        phoneText("android.migration.journal.update.needed"),
        phoneText("android.migration.journal.doesn.t.support.move"),
        phoneText("android.migration.try.again"),
    )
    MigrationSurface.STORAGE -> MigrationCopy(
        phoneText("android.migration.saved.connection.unavailable"),
        phoneText("android.migration.device.couldn.t.read.its"),
        phoneText("android.migration.technical.details"),
    )
    MigrationSurface.LIST_UNAVAILABLE -> MigrationCopy(
        phoneText("android.migration.devices.unavailable"),
        phoneText("android.migration.couldn.t.load.devices.journal"),
        phoneText("android.migration.try.again"),
    )
    MigrationSurface.TARGET_UNAVAILABLE -> MigrationCopy(
        phoneText("android.migration.device.no.longer.available"),
        phoneText("android.migration.that.device.no.longer.listed"),
        phoneText("android.migration.choose.device"),
    )
    MigrationSurface.REFUSED -> MigrationCopy(
        phoneText("android.migration.choice.needs.attention"),
        phoneText("android.migration.journal.couldn.t.apply.choice"),
        phoneText("android.migration.technical.details"),
    )
    MigrationSurface.KEY_REFUSED -> MigrationCopy(
        phoneText("android.migration.pair.again"),
        phoneText("android.migration.device.couldn.t.open.its"),
        phoneText("android.migration.pair.again"),
    )
}
