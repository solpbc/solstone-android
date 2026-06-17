// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.platform.audio.AudioContinuousSourceEngine
import app.solstone.platform.location.AndroidLocationSource
import app.solstone.platform.location.LocationContinuousSourceEngine
import app.solstone.platform.power.FileUsableSpaceProvider
import app.solstone.platform.power.StorageStatus

fun createCaptureSetup(context: Context): CaptureSetup {
    val audio = AudioContinuousSourceEngine(
        outputDirectory = context.cacheDir.resolve("audio-source"),
        storageStatus = StorageStatus(FileUsableSpaceProvider(context.filesDir), MIN_FREE_BYTES),
    )
    val location = LocationContinuousSourceEngine(AndroidLocationSource(context))
    return CaptureSetup(
        engines = listOf(audio, location),
        payloadBytesProvider = PayloadBytesProvider { payload: SegmentPayload ->
            when (payload.sourceId) {
                AudioContinuousSourceEngine.SOURCE_ID -> audio.open(payload)
                LocationContinuousSourceEngine.SOURCE_ID -> location.open(payload)
                else -> error("unknown payload source: ${payload.sourceId}")
            }
        },
    )
}

private const val MIN_FREE_BYTES = 50L * 1024L * 1024L
