// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.camera.camera2

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import app.solstone.platform.camera.still.StillCamera
import app.solstone.platform.camera.still.StillCaptureResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class Camera2StillCamera(context: Context) : StillCamera {
    private val appContext = context.applicationContext

    override fun takeStill(): StillCaptureResult {
        val manager = appContext.getSystemService(CameraManager::class.java)
            ?: return StillCaptureResult.Failure(IllegalStateException("camera manager unavailable"))
        var thread: HandlerThread? = null
        var reader: ImageReader? = null
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        return try {
            val cameraId = chooseCameraId(manager) ?: return StillCaptureResult.Failure(IllegalStateException("no camera available"))
            val size = chooseJpegSize(manager, cameraId) ?: return StillCaptureResult.Failure(IllegalStateException("no jpeg size available"))
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, MAX_IMAGES)
            thread = HandlerThread("solstone-camera2-still").also { it.start() }
            val handler = Handler(thread.looper)
            val bytes = AtomicReference<ByteArray?>()
            val failure = AtomicReference<Throwable?>()
            val imageLatch = CountDownLatch(1)
            reader.setOnImageAvailableListener({ availableReader ->
                try {
                    val image = availableReader.acquireNextImage()
                    image.use {
                        val buffer = it.planes.firstOrNull()?.buffer
                        if (buffer != null) {
                            val data = ByteArray(buffer.remaining())
                            buffer.get(data)
                            if (data.isNotEmpty()) bytes.set(data)
                        }
                    }
                } catch (e: Exception) {
                    failure.set(e)
                } finally {
                    imageLatch.countDown()
                }
            }, handler)

            val sessionLatch = CountDownLatch(1)
            val coordinator = CameraOpenCoordinator<CameraDevice> { it.close() }

            @Suppress("MissingPermission")
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(opened: CameraDevice) {
                        coordinator.onOpened(opened)
                    }

                    override fun onDisconnected(disconnected: CameraDevice) {
                        failure.set(IllegalStateException("camera disconnected"))
                        coordinator.onFailed(disconnected)
                    }

                    override fun onError(errorDevice: CameraDevice, error: Int) {
                        failure.set(IllegalStateException("camera error $error"))
                        coordinator.onFailed(errorDevice)
                    }
                },
                handler,
            )
            val openedDevice = coordinator.awaitOpen(CAPTURE_TIMEOUT_SECONDS * 1_000L)
                ?: return StillCaptureResult.Failure(failure.get() ?: IllegalStateException("camera open timed out"))
            device = openedDevice

            @Suppress("DEPRECATION")
            openedDevice.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        session = configured
                        sessionLatch.countDown()
                    }

                    override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                        failure.set(IllegalStateException("camera session configure failed"))
                        sessionLatch.countDown()
                    }
                },
                handler,
            )
            if (!sessionLatch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return StillCaptureResult.Failure(IllegalStateException("camera session timed out"))
            }
            failure.get()?.let { return StillCaptureResult.Failure(it) }
            val configuredSession = session ?: return StillCaptureResult.Failure(IllegalStateException("camera session unavailable"))
            val request = openedDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                .apply { addTarget(reader.surface) }
                .build()
            configuredSession.capture(request, null, handler)
            if (!imageLatch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                StillCaptureResult.Failure(IllegalStateException("camera image timed out"))
            } else {
                failure.get()?.let { return StillCaptureResult.Failure(it) }
                val data = bytes.get()
                if (data == null) {
                    StillCaptureResult.Failure(IllegalStateException("camera image unavailable"))
                } else {
                    StillCaptureResult.Image(data)
                }
            }
        } catch (e: Exception) {
            StillCaptureResult.Failure(e)
        } finally {
            session?.close()
            device?.close()
            reader?.close()
            thread?.quitSafely()
        }
    }

    private fun chooseCameraId(manager: CameraManager): String? {
        val ids = manager.cameraIdList
        return ids.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull()
    }

    private fun chooseJpegSize(manager: CameraManager, cameraId: String): Size? {
        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        return map.getOutputSizes(ImageFormat.JPEG)
            ?.minByOrNull { size -> size.width.toLong() * size.height.toLong() }
    }

    private companion object {
        const val MAX_IMAGES = 1
        const val CAPTURE_TIMEOUT_SECONDS = 8L
    }
}
