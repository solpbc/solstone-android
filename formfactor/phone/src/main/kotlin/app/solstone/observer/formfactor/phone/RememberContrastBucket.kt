// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import android.app.UiModeManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
fun rememberContrastBucket(): ContrastClass {
    val context = LocalContext.current
    fun read(): Float {
        if (Build.VERSION.SDK_INT >= 34) {
            val uim = context.getSystemService(UiModeManager::class.java) ?: return 0f
            return uim.contrast
        }
        return 0f
    }
    var contrast by remember { mutableFloatStateOf(read()) }
    DisposableEffect(context) {
        if (Build.VERSION.SDK_INT >= 34) {
            val uim = context.getSystemService(UiModeManager::class.java)
            if (uim != null) {
                val listener = UiModeManager.ContrastChangeListener { value -> contrast = value }
                uim.addContrastChangeListener(ContextCompat.getMainExecutor(context), listener)
                return@DisposableEffect onDispose { uim.removeContrastChangeListener(listener) }
            }
        }
        onDispose { }
    }
    return classifyContrast(Build.VERSION.SDK_INT, contrast)
}
