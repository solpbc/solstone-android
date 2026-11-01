// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import java.io.FileOutputStream
import java.nio.ByteBuffer

internal class ScreenPortionFull : Exception()

/** Admission precedes both durable video bytes and the recovery index. */
internal class ScreenSampleWriter(
    private val raw: FileOutputStream,
    private val index: FileOutputStream,
    private val admitFrame: () -> Boolean,
) {
    private val copyBuffer = ByteArray(64 * 1024)
    private var writtenBytes = 0L
    private var writtenSamples = 0

    fun append(buffer: ByteBuffer, pts: Long, flags: Int, configuration: Boolean): Boolean {
        if (!configuration && !admitFrame()) return false
        val size = buffer.remaining()
        check(size in 1..AndroidScreenEncoder.MAX_SAMPLE_BYTES)
        if (writtenBytes + size > ScreenConstants.MAX_RECORD_BYTES ||
            writtenSamples >= ScreenConstants.MAX_RECORD_SAMPLES) throw ScreenPortionFull()
        while (buffer.hasRemaining()) {
            val count = minOf(copyBuffer.size, buffer.remaining())
            buffer.get(copyBuffer, 0, count)
            raw.write(copyBuffer, 0, count)
        }
        // A durable index line can only refer to already durable video bytes.
        raw.fd.sync()
        index.write("$pts,$size,$flags\n".toByteArray(Charsets.US_ASCII))
        index.fd.sync()
        writtenBytes += size
        writtenSamples++
        return true
    }
}
