// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.Button
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.graphics.toArgb

/**
 * The shell's face, applied to the View-based harness screens an OWNER actually reaches.
 *
 * 🔴 **`connect a journal` is the app's main call to action and it does not run on the Compose
 * shell.** The phone shell hands three owner tasks to `ObserverHarnessUi` — connect a journal,
 * manage local storage, and an App Link pairing — and that class was built as operator
 * instrumentation, in raw `android.widget` Views. Its own header says so: *"five of the seven
 * screens below are operator instrumentation."* The other two are not, and they were rendering as
 * grey square-cornered platform buttons over system-font body text, hard against the top of an
 * otherwise empty cream screen, beside a shell made of rounded cream surfaces and the brand face.
 *
 * ⛔ **Operator screens are deliberately NOT styled.** Instrumentation may look like
 * instrumentation; dressing a transport probe in brand chrome would make it look like a place an
 * owner belongs. The harness already draws that seam on `dismiss != null` for the screen margin and
 * for suppressing its drawn back button — this rides the same seam rather than inventing one.
 *
 * ⚠ These mutate the widget in place. ⛔ Do not rebuild it as a Compose surface or a MaterialButton:
 * two runtime tests locate controls with `view is Button`.
 */
fun phoneOwnerButtonStyle(button: Button) {
    val context = button.context
    val accent = SolstoneColors.textOrangeAa.toArgb()
    button.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ShellMetrics.cardRadius.value * context.resources.displayMetrics.density
        setColor(accent)
    }
    button.setTextColor(AndroidColor.WHITE)
    button.isAllCaps = false
    button.typeface = ResourcesCompat.getFont(context, R.font.comfortaa_bold)
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    val padV = (12 * context.resources.displayMetrics.density).toInt()
    button.setPadding(button.paddingLeft, padV, button.paddingRight, padV)
    // ⛔ The platform button carries an elevation and a shadow that read as a raised chip. The
    // shell's own controls are flat on cream.
    button.stateListAnimator = null
    button.elevation = 0f
}

/** Owner-facing body text on those same screens: the shell's ink and reading size, not the default. */
fun phoneOwnerTextStyle(text: TextView) {
    val context = text.context
    text.setTextColor(SolstoneColors.surfaceDark.toArgb())
    text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    text.setLineSpacing(0f, 1.25f)
    val padV = (6 * context.resources.displayMetrics.density).toInt()
    text.setPadding(text.paddingLeft, padV, text.paddingRight, padV)
}
