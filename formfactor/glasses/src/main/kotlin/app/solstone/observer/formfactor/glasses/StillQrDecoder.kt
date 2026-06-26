// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.glasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Collections
import java.util.EnumMap

class StillQrDecoder {
    private val reader = MultiFormatReader()

    init {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = Collections.singletonList(BarcodeFormat.QR_CODE)
        hints[DecodeHintType.TRY_HARDER] = true
        reader.setHints(hints)
    }

    fun decode(input: InputStream): String? =
        try {
            decodeBytes(input.readBytes())
        } catch (_: Exception) {
            null
        }

    private fun decodeBytes(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        var oriented: Bitmap? = null
        return try {
            oriented = orient(decoded, orientation(bytes))
            decodeBitmap(oriented ?: decoded)
        } finally {
            if (oriented != null && oriented !== decoded) {
                oriented.recycle()
            }
            decoded.recycle()
        }
    }

    private fun decodeBitmap(bitmap: Bitmap): String? =
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            reader.decodeWithState(binary).text
        } catch (_: NotFoundException) {
            null
        } finally {
            reader.reset()
        }

    private fun orientation(bytes: ByteArray): Int =
        try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun sampleSize(width: Int, height: Int): Int {
        val longestEdge = maxOf(width, height)
        var sample = 1
        while (longestEdge / sample > MAX_DECODE_EDGE_PX) {
            sample *= 2
        }
        return sample
    }

    private companion object {
        const val MAX_DECODE_EDGE_PX = 1024
    }
}
