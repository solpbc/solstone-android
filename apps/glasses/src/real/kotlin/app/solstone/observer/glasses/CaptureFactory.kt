// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.Context
import android.hardware.SensorManager
import app.solstone.core.metadata.PhotoMetadataContract
import app.solstone.core.metadata.PhotoMetadataEngine
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.platform.audio.AudioContinuousSourceEngine
import app.solstone.platform.camera.camera2.Camera2StillCamera
import app.solstone.platform.camera.still.CameraLock
import app.solstone.platform.camera.still.StillCaptureEngine
import app.solstone.platform.metadata.AndroidBatterySource
import app.solstone.platform.metadata.AndroidImuSensorPort
import app.solstone.platform.metadata.AndroidMetadataScheduler
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
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val metadataEngine = PhotoMetadataEngine(
        scheduler = AndroidMetadataScheduler(),
        battery = AndroidBatterySource(context),
        imu = AndroidImuSensorPort(sensorManager, System::currentTimeMillis),
    )
    val tappedCamera = CameraTapEngine(camera, metadataEngine::onCameraEmission)
    return CaptureSetup(
        engines = listOf(audio, tappedCamera, metadataEngine),
        payloadBytesProvider = object : PayloadBytesProvider {
            override fun open(payload: SegmentPayload) =
                when (payload.sourceId) {
                    AudioContinuousSourceEngine.SOURCE_ID -> audio.open(payload)
                    StillCaptureEngine.SOURCE_ID -> camera.open(payload)
                    PhotoMetadataContract.SOURCE_ID -> metadataEngine.open(payload)
                    else -> error("unknown payload source: ${payload.sourceId}")
                }

            override fun release(payload: SegmentPayload) {
                when (payload.sourceId) {
                    AudioContinuousSourceEngine.SOURCE_ID -> audio.release(payload)
                    StillCaptureEngine.SOURCE_ID -> camera.release(payload)
                    PhotoMetadataContract.SOURCE_ID -> metadataEngine.release(payload)
                    else -> error("unknown payload source: ${payload.sourceId}")
                }
            }
        },
    )
}

private const val MIN_FREE_BYTES = 50L * 1024L * 1024L
