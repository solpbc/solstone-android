// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import app.solstone.observer.formfactor.shared.QrBackend

data class FormFactorSpec(
    val stream: String,
    val deviceLabel: String,
    val handlesPairLinks: Boolean,
    val qrBackend: QrBackend,
    val previewHeightPx: Int,
    val permissions: (sdkInt: Int) -> Array<String>,
)
