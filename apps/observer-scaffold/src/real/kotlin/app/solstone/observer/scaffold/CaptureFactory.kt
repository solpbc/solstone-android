// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import android.content.Context
import android.os.Build
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.observer.harness.SourceRegistration
import app.solstone.platform.audio.AudioContinuousSourceEngine
import app.solstone.platform.camera.camera2.Camera2StillCamera
import app.solstone.platform.camera.legacy.LegacyStillCamera
import app.solstone.platform.camera.still.CameraLock
import app.solstone.platform.camera.still.StillCamera
import app.solstone.platform.camera.still.StillCaptureEngine
import app.solstone.platform.location.AndroidLocationSource
import app.solstone.platform.location.LocationContinuousSourceEngine
import app.solstone.platform.power.FileUsableSpaceProvider
import app.solstone.platform.power.StorageStatus

fun createCaptureSetup(context: Context, cameraLock: CameraLock): CaptureSetup {
    val storageStatus = StorageStatus(FileUsableSpaceProvider(context.filesDir), MIN_FREE_BYTES)
    val audio = AudioContinuousSourceEngine(
        outputDirectory = context.cacheDir.resolve("audio-source"),
        storageStatus = storageStatus,
    )
    val location = LocationContinuousSourceEngine(AndroidLocationSource(context))
    val camera = StillCaptureEngine(
        stillCamera = selectStillCamera(context),
        cameraLock = cameraLock,
    )
    return CaptureSetup(
        registrations = listOf(
            SourceRegistration(
                sourceId = AudioContinuousSourceEngine.SOURCE_ID,
                engine = audio,
                requiredPermissionsGranted = { it.microphoneGranted },
            ),
            SourceRegistration(
                sourceId = LocationContinuousSourceEngine.SOURCE_ID,
                engine = location,
                requiredPermissionsGranted = { it.fineLocationGranted || it.coarseLocationGranted },
            ),
            SourceRegistration(
                sourceId = StillCaptureEngine.SOURCE_ID,
                engine = camera,
                requiredPermissionsGranted = { it.cameraGranted },
            ),
        ),
        payloadBytesProvider = object : PayloadBytesProvider {
            override fun open(payload: SegmentPayload) =
                when (payload.sourceId) {
                    AudioContinuousSourceEngine.SOURCE_ID -> audio.open(payload)
                    LocationContinuousSourceEngine.SOURCE_ID -> location.open(payload)
                    StillCaptureEngine.SOURCE_ID -> camera.open(payload)
                    else -> error("unknown payload source: ${payload.sourceId}")
                }

            override fun release(payload: SegmentPayload) {
                when (payload.sourceId) {
                    AudioContinuousSourceEngine.SOURCE_ID -> audio.release(payload)
                    LocationContinuousSourceEngine.SOURCE_ID -> location.release(payload)
                    StillCaptureEngine.SOURCE_ID -> camera.release(payload)
                    else -> error("unknown payload source: ${payload.sourceId}")
                }
            }
        },
        storageOk = storageStatus::isStorageOk,
    )
}

private fun selectStillCamera(context: Context): StillCamera =
    if (Build.VERSION.SDK_INT >= 29) Camera2StillCamera(context) else LegacyStillCamera(context)

private const val MIN_FREE_BYTES = 50L * 1024L * 1024L
