// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import android.graphics.drawable.ColorDrawable
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun PhoneTheme(content: @Composable () -> Unit) {
    val variant = themeVariant(isSystemInDarkTheme(), rememberContrastBucket())
    PhoneTheme(variant, content)
}

@Composable
fun PhoneTheme(variant: ThemeVariant, content: @Composable () -> Unit) {
    val scheme = SolstoneColorSchemes.scheme(variant)
    ApplySystemBarPolarity(variant)
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun ApplySystemBarPolarity(variant: ThemeVariant) {
    val view = LocalView.current
    val activity = LocalActivity.current
    val lightBars = variant.isLightGround
    val backgroundArgb = SolstoneColorSchemes.scheme(variant).background.toArgb()
    if (view.isInEditMode || activity == null) return
    SideEffect {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        // isAppearanceLightStatusBars names the BAR, not the icons:
        // true → light bar → dark icons → correct on cream.
        // Settled from AOSP APPEARANCE_LIGHT_STATUS_BARS.
        controller.isAppearanceLightStatusBars = lightBars
        controller.isAppearanceLightNavigationBars = lightBars
        // Paint the window background with the applied ground so the
        // padded host does not reveal XML Theme.Material.Light
        // in the system-bar region. Not an inset read.
        activity.window.setBackgroundDrawable(ColorDrawable(backgroundArgb))
    }
}
