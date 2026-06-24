// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.glasses

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Camera
import android.view.SurfaceHolder
import android.view.SurfaceView
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
class QrPreviewView(
    context: Context,
    private val controller: HarnessController,
    private val status: (String) -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback, Camera.PreviewCallback {
    private val reader = MultiFormatReader()
    private val decoding = AtomicBoolean(false)
    private var camera: Camera? = null
    private var width = 0
    private var height = 0

    init {
        holder.addCallback(this)
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = Collections.singletonList(BarcodeFormat.QR_CODE)
        hints[DecodeHintType.TRY_HARDER] = true
        reader.setHints(hints)
    }

    @SuppressLint("MissingPermission")
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!controller.beginScanSession()) {
            status("Camera busy")
            return
        }
        try {
            val opened = Camera.open()
            camera = opened
            opened.setPreviewDisplay(holder)
            val params = opened.parameters
            params.previewFormat = ImageFormat.NV21
            val size = params.previewSize
            width = size.width
            height = size.height
            opened.parameters = params
            opened.setPreviewCallback(this)
            opened.startPreview()
            status("Scanning")
        } catch (e: Exception) {
            status("Camera error: ${e.message ?: "unknown"}")
            releaseCamera()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseCamera()
    }

    override fun onPreviewFrame(data: ByteArray, camera: Camera) {
        if (!decoding.compareAndSet(false, true)) return
        try {
            val text = decode(data, width, height)
            if (text != null && looksLikePairLink(text)) {
                controller.onScannedPairLink(text)
                status("Paired")
                releaseCamera()
            }
        } finally {
            decoding.set(false)
        }
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

    private fun releaseCamera() {
        camera?.setPreviewCallback(null)
        camera?.stopPreview()
        camera?.release()
        camera = null
        controller.endScanSession()
    }
}
