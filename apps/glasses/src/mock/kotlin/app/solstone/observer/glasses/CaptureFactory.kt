// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.Context
import app.solstone.core.metadata.BatterySnapshot
import app.solstone.core.metadata.BatteryStatus
import app.solstone.core.metadata.ImuHandle
import app.solstone.core.metadata.ImuListener
import app.solstone.core.metadata.ImuSensorPort
import app.solstone.core.metadata.PhotoMetadataContract
import app.solstone.core.metadata.PhotoMetadataEngine
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.platform.metadata.AndroidMetadataScheduler
import app.solstone.platform.camera.still.CameraLock
import app.solstone.testing.FakeContinuousSource
import app.solstone.testing.FakeBatterySource
import app.solstone.testing.fakePayloadBytes
import java.io.ByteArrayInputStream

fun createCaptureSetup(context: Context, cameraLock: CameraLock): CaptureSetup {
    val audio = FakeContinuousSource(
        sourceId = "audio",
        stream = MAIN_STREAM,
        frameEveryMillis = 300_000,
        frameSizeBytes = 16,
        frameCount = 1,
        fixedPayloadName = "audio.m4a",
        mediaType = "audio/mp4",
    )
    val camera = FakeContinuousSource(
        sourceId = "camera",
        stream = MAIN_STREAM,
        frameEveryMillis = 15_000,
        frameSizeBytes = 16,
        frameCount = 1,
        fixedPayloadName = "camera-0.jpg",
        mediaType = "image/jpeg",
    )
    val metadataEngine = PhotoMetadataEngine(
        scheduler = AndroidMetadataScheduler(),
        battery = FakeBatterySource(BatterySnapshot(level = 88, status = BatteryStatus.DISCHARGING, tempC = 30.0)),
        imu = EmptyImuSensorPort,
    )
    val tappedCamera = CameraTapEngine(camera, metadataEngine::onCameraEmission)
    return CaptureSetup(
        engines = listOf(audio, tappedCamera, metadataEngine),
        payloadBytesProvider = object : PayloadBytesProvider {
            override fun open(payload: SegmentPayload) =
                when (payload.sourceId) {
                    PhotoMetadataContract.SOURCE_ID -> metadataEngine.open(payload)
                    else -> ByteArrayInputStream(fakePayloadBytes(payload.sourceId, payload.ref.name, 0, payload.ref.byteSize.toInt()))
                }

            override fun release(payload: SegmentPayload) {
                if (payload.sourceId == PhotoMetadataContract.SOURCE_ID) metadataEngine.release(payload)
            }
        },
    )
}

private object EmptyImuSensorPort : ImuSensorPort {
    override fun start(listener: ImuListener): ImuHandle =
        object : ImuHandle {
            override fun stop() = Unit
        }
}
