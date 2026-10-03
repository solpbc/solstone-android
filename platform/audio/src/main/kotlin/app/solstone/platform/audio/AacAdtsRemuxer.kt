// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

data class AacRemux(val sampleDurationMs: Long, val outputBytes: Long)

fun interface AacAdtsRemuxer {
    fun remux(adts: File, m4a: File): AacRemux
}

class AndroidAacAdtsRemuxer : AacAdtsRemuxer {
    override fun remux(adts: File, m4a: File): AacRemux {
        if (!adts.exists() || adts.length() <= 0L) {
            return AacRemux(sampleDurationMs = 0L, outputBytes = 0L)
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(adts.absolutePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex == -1 || audioFormat == null) {
                return AacRemux(sampleDurationMs = 0L, outputBytes = 0L)
            }
            extractor.selectTrack(audioTrackIndex)

            val muxer = MediaMuxer(m4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var sampleCount = 0
            var firstSampleTimeUs: Long = -1L
            var lastSampleTimeUs: Long = -1L
            var durationUs: Long = 0L

            try {
                val muxerTrackIndex = muxer.addTrack(audioFormat)
                muxer.start()

                val maxInputSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } else {
                    64 * 1024
                }
                val buffer = ByteBuffer.allocate(maxOf(maxInputSize, 64 * 1024))
                val bufferInfo = MediaCodec.BufferInfo()

                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    val sampleTimeUs = extractor.sampleTime
                    val sampleFlags = extractor.sampleFlags

                    if (firstSampleTimeUs == -1L) {
                        firstSampleTimeUs = sampleTimeUs
                    }
                    lastSampleTimeUs = sampleTimeUs

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = sampleTimeUs
                    // Extractor and codec share some numeric values for different meanings.
                    // SAMPLE_FLAG_PARTIAL_FRAME is 4, which is BUFFER_FLAG_END_OF_STREAM.
                    var codecFlags = 0
                    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                    }
                    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                    }
                    bufferInfo.flags = codecFlags

                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                    sampleCount++
                    extractor.advance()
                }

                if (sampleCount > 0) {
                    val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else {
                        AudioContinuousSourceEngine.SAMPLE_RATE_HZ
                    }
                    val samplesDurationUs = (sampleCount.toLong() * 1024L * 1_000_000L) / sampleRate.toLong()
                    val presentationDeltaUs = if (firstSampleTimeUs >= 0L && lastSampleTimeUs >= firstSampleTimeUs) {
                        (lastSampleTimeUs - firstSampleTimeUs) + (1024L * 1_000_000L / sampleRate.toLong())
                    } else {
                        0L
                    }
                    durationUs = maxOf(samplesDurationUs, presentationDeltaUs)
                }
            } finally {
                runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }

            val durationMs = if (sampleCount > 0) maxOf(1L, durationUs / 1000L) else 0L
            val outBytes = if (sampleCount > 0 && m4a.exists()) m4a.length() else 0L
            if (sampleCount == 0 || outBytes <= 0L) {
                m4a.delete()
                return AacRemux(sampleDurationMs = 0L, outputBytes = 0L)
            }
            return AacRemux(sampleDurationMs = durationMs, outputBytes = outBytes)
        } finally {
            runCatching { extractor.release() }
        }
    }
}
