// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

interface ScreenEncoder {
    fun setFrameAdmission(allowed: () -> Boolean) {}
    fun inputSurface(): Any?
    fun start(outputH264: File, outputIndex: File, onFirstFrame: (Long) -> Unit)
    fun stop(): Long
    fun release()
    fun failure(): Throwable? = null
    fun sizeLimitReached(): Boolean = false
}

fun interface ScreenEncoderFactory {
    fun create(width: Int, height: Int, bitRate: Int, frameRate: Int): ScreenEncoder
}

class AndroidScreenEncoder(
    width: Int,
    height: Int,
    bitRate: Int = ScreenConstants.BIT_RATE,
    frameRate: Int = ScreenConstants.FRAME_RATE,
) : ScreenEncoder {
    private val codec = MediaCodec.createEncoderByType(ScreenConstants.MIME_TYPE)
    private var surface: Surface? = null
    private val draining = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var error: Throwable? = null
    @Volatile private var sampleCount = 0L
    @Volatile private var atSizeLimit = false
    private var started = false
    private var frameAdmission: () -> Boolean = { true }

    override fun setFrameAdmission(allowed: () -> Boolean) { frameAdmission = allowed }

    init {
        try {
            val format = MediaFormat.createVideoFormat(ScreenConstants.MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, ScreenConstants.I_FRAME_INTERVAL_SECONDS)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / frameRate)
                setFloat("max-fps-to-encoder", frameRate.toFloat())
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
        } catch (t: Throwable) {
            surface?.release()
            codec.release()
            throw t
        }
    }

    override fun inputSurface(): Any? = surface
    override fun failure(): Throwable? = error
    override fun sizeLimitReached(): Boolean = atSizeLimit

    override fun start(outputH264: File, outputIndex: File, onFirstFrame: (Long) -> Unit) {
        check(!started)
        codec.start()
        started = true
        draining.set(true)
        thread = Thread({
            try {
                FileOutputStream(outputH264).use { raw ->
                    FileOutputStream(outputIndex).use { index ->
                        val writer = ScreenSampleWriter(raw, index, frameAdmission)
                        val info = MediaCodec.BufferInfo()
                        var firstFrame = true
                        while (draining.get()) {
                            val slot = codec.dequeueOutputBuffer(info, 10_000L)
                            if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                val format = codec.outputFormat
                                for (key in listOf("csd-0", "csd-1")) {
                                    format.getByteBuffer(key)?.duplicate()?.let {
                                        if (it.hasRemaining()) writer.append(it, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, configuration = true)
                                    }
                                }
                            } else if (slot >= 0) {
                                try {
                                    if (info.size > 0) {
                                        val buffer = checkNotNull(codec.getOutputBuffer(slot)).duplicate()
                                        buffer.position(info.offset)
                                        buffer.limit(info.offset + info.size)
                                        val configuration = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                        val written = writer.append(buffer, info.presentationTimeUs, info.flags, configuration)
                                        if (written && !configuration) {
                                            if (firstFrame) {
                                                firstFrame = false
                                                // Surface timestamps use the monotonic clock. Convert once;
                                                // never treat codec configuration as a captured frame.
                                                val ageUs = (System.nanoTime() / 1_000L - info.presentationTimeUs).coerceIn(0L, 10_000_000L)
                                                onFirstFrame(System.currentTimeMillis() - ageUs / 1_000L)
                                            }
                                            sampleCount++
                                        }
                                    }
                                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                                } finally {
                                    codec.releaseOutputBuffer(slot, false)
                                }
                            }
                        }
                    }
                }
            } catch (_: ScreenPortionFull) {
                atSizeLimit = true
            } catch (t: Throwable) {
                error = t
            } finally {
                draining.set(false)
            }
        }, "ScreenEncoderDrainer").also { it.start() }
    }

    override fun stop(): Long {
        if (!started) return sampleCount
        runCatching { codec.signalEndOfInputStream() }.onFailure { if (error == null) error = it }
        val interrupted = Thread.interrupted()
        try {
            thread?.join(3_000L)
            if (thread?.isAlive == true) {
                draining.set(false)
                thread?.join(1_000L)
                if (error == null) error = IllegalStateException("screen encoder drain timed out")
            }
            codec.stop()
        } finally {
            started = false
            if (interrupted) Thread.currentThread().interrupt()
        }
        return sampleCount
    }

    override fun release() {
        runCatching { stop() }
        surface?.release()
        surface = null
        codec.release()
    }

    companion object {
        const val MAX_SAMPLE_BYTES = 8 * 1024 * 1024
    }

}
