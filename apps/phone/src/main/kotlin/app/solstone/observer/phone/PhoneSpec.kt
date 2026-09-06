// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import app.solstone.core.sources.PHONE_STREAM
import app.solstone.observer.formfactor.shared.QrBackend
import app.solstone.observer.scaffold.FormFactorSpec

val PHONE_DECLARED_CAPTURE_FOREGROUND_TYPES = setOf("microphone", "location", "camera")

val phoneSpec = FormFactorSpec(
    stream = PHONE_STREAM,
    deviceLabel = "solstone phone",
    handlesPairLinks = true,
    qrBackend = QrBackend.Camera2,
    previewHeightPx = 480,
    declaredCaptureForegroundTypes = PHONE_DECLARED_CAPTURE_FOREGROUND_TYPES,
    permissions = { sdkInt ->
        if (sdkInt >= 33) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }
    },
)
