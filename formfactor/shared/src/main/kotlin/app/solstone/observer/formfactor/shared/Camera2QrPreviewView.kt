// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import app.solstone.core.pl.looksLikePairLink
import app.solstone.observer.harness.HarnessController
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.Collections
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean

class Camera2QrPreviewView(
    context: Context,
    private val controller: HarnessController,
    threadLabel: String,
    private val status: (String) -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {
    private val reader = MultiFormatReader()
    private val decoding = AtomicBoolean(false)
    private val threadName = "solstone-$threadLabel-qr"
    private var scanSessionHeld = false
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    @Volatile private var closed = false

    init {
        surfaceTextureListener = this
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = Collections.singletonList(BarcodeFormat.QR_CODE)
        hints[DecodeHintType.TRY_HARDER] = true
        reader.setHints(hints)
    }

    @SuppressLint("MissingPermission")
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        closed = false
        if (!controller.beginScanSession()) {
            status("Camera busy")
            return
        }
        scanSessionHeld = true
        try {
            val manager = context.getSystemService(CameraManager::class.java)
            val cameraId = manager?.let { chooseCameraId(it) }
            if (manager == null || cameraId == null) {
                report("Camera error: unavailable")
                releaseCamera()
                return
            }
            val size = chooseYuvSize(manager, cameraId) ?: Size(width.coerceAtLeast(1), height.coerceAtLeast(1))
            surface.setDefaultBufferSize(size.width, size.height)
            previewSurface = Surface(surface)
            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, MAX_IMAGES)
            cameraThread = HandlerThread(threadName).also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)
            imageReader?.setOnImageAvailableListener({ reader -> onImageAvailable(reader) }, cameraHandler)
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(opened: CameraDevice) {
                        if (closed) {
                            opened.close()
                            return
                        }
                        camera = opened
                        createSession(opened)
                    }

                    override fun onDisconnected(disconnected: CameraDevice) {
                        disconnected.close()
                        report("Camera error: disconnected")
                        releaseCamera()
                    }

                    override fun onError(errorDevice: CameraDevice, error: Int) {
                        errorDevice.close()
                        report("Camera error: $error")
                        releaseCamera()
                    }
                },
                cameraHandler,
            )
            report("Scanning")
        } catch (e: Exception) {
            report("Camera error: ${e.message ?: "unknown"}")
            releaseCamera()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releaseCamera()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    @Suppress("DEPRECATION")
    private fun createSession(opened: CameraDevice) {
        if (closed) return
        val preview = previewSurface ?: return
        val analysis = imageReader?.surface ?: return
        try {
            opened.createCaptureSession(
                listOf(preview, analysis),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        session = configured
                        try {
                            val request = opened.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                .apply {
                                    addTarget(preview)
                                    addTarget(analysis)
                                }
                                .build()
                            configured.setRepeatingRequest(request, null, cameraHandler)
                        } catch (e: Exception) {
                            report("Camera error: ${e.message ?: "unknown"}")
                            releaseCamera()
                        }
                    }

                    override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                        report("Camera error: session configure failed")
                        releaseCamera()
                    }
                },
                cameraHandler,
            )
        } catch (e: Exception) {
            report("Camera error: ${e.message ?: "unknown"}")
            releaseCamera()
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        if (closed) return
        val image = reader.acquireLatestImage() ?: return
        image.use { current ->
            if (!decoding.compareAndSet(false, true)) return
            try {
                val text = decode(current)
                if (text != null) handleDecodedText(text)
            } finally {
                decoding.set(false)
            }
        }
    }

    fun submitDecodedTextForTest(rawText: String) {
        closed = false
        val handler = ensureQrHandler()
        handler.post { handleDecodedText(rawText) }
    }

    private fun handleDecodedText(text: String) {
        if (looksLikePairLink(text) && !closed) {
            val outcome = controller.onScannedPairLinkClassified(text)
            report(pairStatusText(outcome))
            if (outcome.isSuccessfulPair()) {
                releaseCamera()
            }
        }
    }

    private fun ensureQrHandler(): Handler {
        cameraHandler?.let { return it }
        val thread = HandlerThread(threadName).also { it.start() }
        cameraThread = thread
        return Handler(thread.looper).also { cameraHandler = it }
    }

    private fun decode(image: Image): String? =
        try {
            val data = luminanceBytes(image)
            val source = PlanarYUVLuminanceSource(data, image.width, image.height, 0, 0, image.width, image.height, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            reader.decodeWithState(bitmap).text
        } catch (_: NotFoundException) {
            null
        } finally {
            reader.reset()
        }

    private fun luminanceBytes(image: Image): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        val data = ByteArray(width * height)
        var output = 0
        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (col in 0 until width) {
                data[output++] = buffer.get(rowStart + col * pixelStride)
            }
        }
        return data
    }

    @Synchronized
    private fun releaseCamera() {
        closed = true
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { releaseCameraOnCameraThread() }
        } else {
            releaseCameraOnCameraThread()
        }
    }

    private fun releaseCameraOnCameraThread() {
        closed = true
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { previewSurface?.release() }
        previewSurface = null
        val thread = cameraThread
        cameraThread = null
        cameraHandler = null
        thread?.quitSafely()
        if (scanSessionHeld) {
            scanSessionHeld = false
            controller.endScanSession()
        }
    }

    private fun chooseCameraId(manager: CameraManager): String? {
        val ids = manager.cameraIdList
        return ids.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull()
    }

    private fun chooseYuvSize(manager: CameraManager, cameraId: String): Size? {
        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        return map.getOutputSizes(ImageFormat.YUV_420_888)
            ?.minByOrNull { size -> size.width.toLong() * size.height.toLong() }
    }

    private fun report(message: String) {
        post { if (!closed || message != "Scanning") status(message) }
    }

    private companion object {
        const val MAX_IMAGES = 2
    }
}
