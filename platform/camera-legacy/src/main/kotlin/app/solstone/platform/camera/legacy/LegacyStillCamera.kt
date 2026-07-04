// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
@file:Suppress("DEPRECATION")

package app.solstone.platform.camera.legacy

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import app.solstone.platform.camera.still.StillCamera
import app.solstone.platform.camera.still.StillCaptureResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Suppress("UNUSED_PARAMETER")
class LegacyStillCamera(context: Context) : StillCamera {
    override fun takeStill(): StillCaptureResult {
        var camera: Camera? = null
        var texture: SurfaceTexture? = null
        return try {
            camera = Camera.open(CAMERA_ID)
            val parameters = camera.parameters
            chooseSmallest(parameters.supportedPictureSizes)?.let { size ->
                parameters.setPictureSize(size.width, size.height)
            }
            parameters.jpegQuality = JPEG_QUALITY
            camera.parameters = parameters

            texture = SurfaceTexture(SURFACE_TEXTURE_NAME)
            camera.setPreviewTexture(texture)
            camera.startPreview()
            try {
                Thread.sleep(PREVIEW_WARMUP_MS)
            } catch (e: InterruptedException) {
                return interruptedStillFailure(e)
            }

            val latch = CountDownLatch(1)
            val holder = arrayOfNulls<ByteArray>(1)
            camera.takePicture(null, null) { data, _ ->
                if (data != null && data.isNotEmpty()) {
                    holder[0] = data
                }
                latch.countDown()
            }
            if (!latch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                StillCaptureResult.Failure(IllegalStateException("legacy camera capture timed out"))
            } else {
                val data = holder[0]
                if (data == null) {
                    StillCaptureResult.Failure(IllegalStateException("legacy camera image unavailable"))
                } else {
                    StillCaptureResult.Image(data)
                }
            }
        } catch (e: Exception) {
            StillCaptureResult.Failure(e)
        } finally {
            if (camera != null) {
                try {
                    camera.stopPreview()
                } catch (_: RuntimeException) {
                    // Some Android 9 camera HALs throw if preview already stopped.
                }
                camera.release()
            }
            texture?.release()
        }
    }

    private fun chooseSmallest(sizes: List<Camera.Size>?): Camera.Size? =
        sizes?.minByOrNull { size -> size.width.toLong() * size.height.toLong() }

    private companion object {
        const val CAMERA_ID = 0
        const val SURFACE_TEXTURE_NAME = 110
        const val JPEG_QUALITY = 80
        const val PREVIEW_WARMUP_MS = 800L
        const val CAPTURE_TIMEOUT_SECONDS = 8L
    }
}

internal fun interruptedStillFailure(error: InterruptedException): StillCaptureResult {
    Thread.currentThread().interrupt()
    return StillCaptureResult.Failure(error)
}
