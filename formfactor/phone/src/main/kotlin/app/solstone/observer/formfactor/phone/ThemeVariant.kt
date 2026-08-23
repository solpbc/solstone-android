// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

enum class ThemeVariant {
    LIGHT_STANDARD,
    LIGHT_MEDIUM,
    LIGHT_HIGH,
    DARK_STANDARD,
    DARK_MEDIUM,
    DARK_HIGH,
}

val ThemeVariant.isLightGround: Boolean
    get() = this == ThemeVariant.LIGHT_STANDARD ||
        this == ThemeVariant.LIGHT_MEDIUM ||
        this == ThemeVariant.LIGHT_HIGH

fun themeVariant(isDark: Boolean, bucket: ContrastClass): ThemeVariant =
    when (bucket) {
        ContrastClass.STANDARD -> if (isDark) ThemeVariant.DARK_STANDARD else ThemeVariant.LIGHT_STANDARD
        ContrastClass.MEDIUM -> if (isDark) ThemeVariant.DARK_MEDIUM else ThemeVariant.LIGHT_MEDIUM
        ContrastClass.HIGH -> if (isDark) ThemeVariant.DARK_HIGH else ThemeVariant.LIGHT_HIGH
    }
