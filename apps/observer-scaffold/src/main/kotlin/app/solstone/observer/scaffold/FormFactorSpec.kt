// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import android.content.Context
import android.widget.Button
import android.widget.TextView
import android.view.View
import app.solstone.observer.formfactor.shared.QrBackend

data class FormFactorSpec(
    val stream: String,
    val deviceLabel: String,
    val handlesPairLinks: Boolean,
    val qrBackend: QrBackend,
    val previewHeightPx: Int,
    val declaredCaptureForegroundTypes: Set<String>,
    val permissions: (sdkInt: Int) -> Array<String>,
    /** The accessory must call [onConfirmed] before the pairing screen offers its exit. */
    val pairingAccessoryFactory: ((context: Context, onConfirmed: () -> Unit) -> View)? = null,
    /** How owner-facing harness controls and text are dressed; null keeps the platform default. */
    val ownerButtonStyle: ((Button) -> Unit)? = null,
    val ownerTextStyle: ((TextView) -> Unit)? = null,
)
