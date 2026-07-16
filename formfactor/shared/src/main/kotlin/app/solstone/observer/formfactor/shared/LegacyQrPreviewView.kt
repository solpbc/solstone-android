// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Surface
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

@Suppress("DEPRECATION")
class LegacyQrPreviewView(
    context: Context,
    private val controller: HarnessController,
    threadLabel: String,
    private val status: (String) -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback, Camera.PreviewCallback {
    private val reader = MultiFormatReader()
    private val decoding = AtomicBoolean(false)
    private val threadName = "solstone-$threadLabel-qr"
    private var scanSessionHeld = false
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var camera: Camera? = null
    private var viewWidth = 0
    private var viewHeight = 0
    private var bufferWidth = 0
    private var bufferHeight = 0
    @Volatile private var closed = false

    init {
        holder.addCallback(this)
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = Collections.singletonList(BarcodeFormat.QR_CODE)
        hints[DecodeHintType.TRY_HARDER] = true
        reader.setHints(hints)
    }

    @SuppressLint("MissingPermission")
    override fun surfaceCreated(holder: SurfaceHolder) {
        closed = false
        if (!controller.beginScanSession()) {
            status("Camera busy")
            return
        }
        scanSessionHeld = true
        cameraThread = HandlerThread(threadName).also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        cameraHandler?.post {
            try {
                val opened = Camera.open()
                if (closed) {
                    opened.release()
                    return@post
                }
                camera = opened
                opened.setPreviewDisplay(holder)
                val params = opened.parameters
                params.previewFormat = ImageFormat.NV21
                val size = params.previewSize
                bufferWidth = size.width
                bufferHeight = size.height
                schedulePreviewTransform()
                opened.parameters = params
                opened.setPreviewCallback(this)
                opened.startPreview()
                report("Scanning")
            } catch (e: Exception) {
                report("Camera error: ${e.message ?: "unknown"}")
                releaseCamera()
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        schedulePreviewTransform()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseCamera()
    }

    override fun onPreviewFrame(data: ByteArray, camera: Camera) {
        val handler = cameraHandler ?: return
        if (!decoding.compareAndSet(false, true)) return
        val frame = data.copyOf()
        handler.post {
            try {
                if (closed) return@post
                val text = decode(frame, bufferWidth, bufferHeight)
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

    private fun schedulePreviewTransform() {
        post {
            if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return@post
            val transform = qrPreviewTransform(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                rotationDegrees = displayRotationDegrees(),
            )
            pivotX = transform.pivotX
            pivotY = transform.pivotY
            scaleX = transform.scaleX
            scaleY = transform.scaleY
        }
    }

    private fun displayRotationDegrees(): Int =
        when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

    private fun decode(data: ByteArray, width: Int, height: Int): String? =
        try {
            val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            reader.decodeWithState(bitmap).text
        } catch (_: NotFoundException) {
            null
        } finally {
            reader.reset()
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
        camera?.setPreviewCallback(null)
        runCatching { camera?.stopPreview() }
        runCatching { camera?.release() }
        camera = null
        bufferWidth = 0
        bufferHeight = 0
        val thread = cameraThread
        cameraThread = null
        cameraHandler = null
        thread?.quitSafely()
        if (scanSessionHeld) {
            scanSessionHeld = false
            controller.endScanSession()
        }
    }

    private fun report(message: String) {
        post { if (!closed || message != "Scanning") status(message) }
    }
}
