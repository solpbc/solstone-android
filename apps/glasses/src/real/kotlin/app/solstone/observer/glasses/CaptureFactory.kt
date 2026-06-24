// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.Context
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.platform.audio.AudioContinuousSourceEngine
import app.solstone.platform.camera.camera2.Camera2StillCamera
import app.solstone.platform.camera.still.CameraLock
import app.solstone.platform.camera.still.StillCaptureEngine
import app.solstone.platform.power.FileUsableSpaceProvider
import app.solstone.platform.power.StorageStatus

fun createCaptureSetup(context: Context, cameraLock: CameraLock): CaptureSetup {
    val audio = AudioContinuousSourceEngine(
        outputDirectory = context.cacheDir.resolve("audio-source"),
        storageStatus = StorageStatus(FileUsableSpaceProvider(context.filesDir), MIN_FREE_BYTES),
    )
    val camera = StillCaptureEngine(
        stillCamera = Camera2StillCamera(context),
        cameraLock = cameraLock,
    )
    return CaptureSetup(
        engines = listOf(audio, camera),
        payloadBytesProvider = PayloadBytesProvider { payload: SegmentPayload ->
            when (payload.sourceId) {
                AudioContinuousSourceEngine.SOURCE_ID -> audio.open(payload)
                StillCaptureEngine.SOURCE_ID -> camera.open(payload)
                else -> error("unknown payload source: ${payload.sourceId}")
            }
        },
    )
}

private const val MIN_FREE_BYTES = 50L * 1024L * 1024L
