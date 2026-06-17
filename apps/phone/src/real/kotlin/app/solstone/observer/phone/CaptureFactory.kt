// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import app.solstone.core.segment.SegmenterAnchor
import app.solstone.platform.audio.AudioContinuousSourceEngine
import app.solstone.platform.power.FileUsableSpaceProvider
import app.solstone.platform.power.StorageStatus
import java.time.ZoneId

fun createCaptureSetup(context: Context): CaptureSetup {
    val engine = AudioContinuousSourceEngine(
        outputDirectory = context.cacheDir.resolve("audio-source"),
        storageStatus = StorageStatus(FileUsableSpaceProvider(context.filesDir), MIN_FREE_BYTES),
    )
    return CaptureSetup(
        engine = engine,
        clock = engine.clock,
        anchor = SegmenterAnchor(System.currentTimeMillis(), 0, ZoneId.systemDefault()),
        payloadBytesProvider = engine,
    )
}

private const val MIN_FREE_BYTES = 50L * 1024L * 1024L
