// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.folders

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import app.solstone.core.folders.DEFAULT_MAX_IMAGE_PIXELS
import app.solstone.core.folders.FileKind
import app.solstone.core.observer.HeicExif
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Converts device-only image formats (`heic`, `heif`, `tif`, `tiff`, `gif`, `bmp`) into
 * journal-accepted PNG (for screenshots) or JPEG with preserved EXIF (for photos).
 */
internal object FolderImageConverter {
    fun convert(
        sourceFile: File,
        extension: String,
        kind: FileKind,
        targetFile: File,
        maxPixels: Long = DEFAULT_MAX_IMAGE_PIXELS,
    ): Boolean = runCatching {
        val bitmap = decodeChecked(sourceFile, maxPixels) ?: return false
        val encoded = try {
            val out = ByteArrayOutputStream()
            val ok = when (kind) {
                FileKind.SCREENSHOT -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                else -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            if (!ok) return false
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }

        val finalBytes = if (kind != FileKind.SCREENSHOT && (extension == "heic" || extension == "heif")) {
            runCatching { HeicExif.carryToUprightJpeg(sourceFile.readBytes(), encoded) }.getOrDefault(encoded)
        } else {
            encoded
        }
        targetFile.writeBytes(finalBytes)
        true
    }.getOrDefault(false)

    private fun decodeChecked(sourceFile: File, maxPixels: Long): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(sourceFile)
            var tooBig = false
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val pixels = info.size.width.toLong() * info.size.height.toLong()
                if (pixels <= 0L || pixels > maxPixels) {
                    tooBig = true
                }
            }
            if (tooBig) {
                bitmap.recycle()
                return null
            }
            return bitmap
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        if (pixels <= 0L || pixels > maxPixels) return null
        return BitmapFactory.decodeFile(sourceFile.absolutePath)
    }
}
