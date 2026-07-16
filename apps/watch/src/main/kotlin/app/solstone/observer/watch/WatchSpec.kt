// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.watch

import android.Manifest
import app.solstone.core.sources.WATCH_STREAM
import app.solstone.observer.formfactor.shared.QrBackend
import app.solstone.observer.scaffold.FormFactorSpec

val watchSpec = FormFactorSpec(
    stream = WATCH_STREAM,
    deviceLabel = "solstone watch",
    handlesPairLinks = false,
    qrBackend = QrBackend.Legacy,
    previewHeightPx = 220,
    permissions = { sdkInt ->
        if (sdkInt >= 33) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }
    },
)
