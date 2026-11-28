// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.folders

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.folders.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class FolderImageAndSafTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun imageConverterProducesValidPngForScreenshotAndJpegForPhoto() {
        val src = temp.newFile("sample.png")
        val bmp = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        FileOutputStream(src).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()

        val shotOut = File(temp.root, "c_01.png")
        assertTrue(FolderImageConverter.convert(src, "png", FileKind.SCREENSHOT, shotOut))
        assertTrue(shotOut.isFile && shotOut.length() > 8L)
        val shotBytes = shotOut.readBytes()
        assertEquals(0x89.toByte(), shotBytes[0])
        assertEquals('P'.code.toByte(), shotBytes[1])

        val photoOut = File(temp.root, "c_02.jpg")
        assertTrue(FolderImageConverter.convert(src, "png", FileKind.PHOTO, photoOut))
        assertTrue(photoOut.isFile && photoOut.length() > 4L)
        val decoded = BitmapFactory.decodeFile(photoOut.absolutePath)
        assertNotNull(decoded)
        assertEquals(16, decoded!!.width)
        assertEquals(12, decoded.height)
        decoded.recycle()
    }

    @Test
    fun imageConverterRejectsOversizedPixelBudget() {
        val src = temp.newFile("tiny.png")
        val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        FileOutputStream(src).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()

        val out = File(temp.root, "refused.jpg")
        assertFalse(FolderImageConverter.convert(src, "png", FileKind.PHOTO, out, maxPixels = 100L))
    }

    @Test
    fun canonicalPathAndFriendlyLabelNormalizeExternalStorageTreeIds() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FWork")
        assertEquals("primary:Documents/Work", AndroidFolderFileSystem.canonicalPathFor(uri, "primary:Documents/Work"))
        assertEquals("Documents › Work", AndroidFolderFileSystem.friendlyLabelFor(uri, "primary:Documents/Work", "Work"))
    }
}
