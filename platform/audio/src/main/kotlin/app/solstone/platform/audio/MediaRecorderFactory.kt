// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.audio

import android.media.MediaRecorder
import java.io.File

class MediaRecorderFactory : AudioRecorderFactory {
    override fun create(output: File): AudioRecording = MediaRecording(output)
}

private class MediaRecording(private val output: File) : AudioRecording {
    private var recorder: MediaRecorder? = null

    override fun start() {
        val localRecorder = MediaRecorder()
        recorder = localRecorder
        localRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        localRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        localRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        localRecorder.setAudioSamplingRate(AudioContinuousSourceEngine.SAMPLE_RATE_HZ)
        localRecorder.setAudioChannels(AudioContinuousSourceEngine.CHANNELS)
        localRecorder.setAudioEncodingBitRate(AudioContinuousSourceEngine.BIT_RATE)
        localRecorder.setOutputFile(output.absolutePath)
        localRecorder.prepare()
        localRecorder.start()
    }

    override fun finish(): Long {
        val localRecorder = recorder
        if (localRecorder != null) {
            runCatching { localRecorder.stop() }
            runCatching { localRecorder.release() }
            recorder = null
        }
        return output.length()
    }

    override fun discard() {
        val localRecorder = recorder
        if (localRecorder != null) {
            runCatching { localRecorder.stop() }
            runCatching { localRecorder.release() }
            recorder = null
        }
        output.delete()
    }
}
