// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.audio

import android.media.MediaRecorder
import android.os.Build
import app.solstone.core.model.SilencedFact
import java.io.File

class MediaRecorderFactory : AudioRecorderFactory {
    override fun create(output: File): AudioRecording = MediaRecording(output)
}

private class MediaRecording(private val output: File) : AudioRecording {
    private val lock = Any()
    private var recorder: MediaRecorder? = null

    override fun start() {
        val localRecorder = MediaRecorder()
        try {
            localRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            localRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            localRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            localRecorder.setAudioSamplingRate(AudioContinuousSourceEngine.SAMPLE_RATE_HZ)
            localRecorder.setAudioChannels(AudioContinuousSourceEngine.CHANNELS)
            localRecorder.setAudioEncodingBitRate(AudioContinuousSourceEngine.BIT_RATE)
            localRecorder.setOutputFile(output.absolutePath)
            localRecorder.prepare()
            localRecorder.start()
            synchronized(lock) { recorder = localRecorder }
        } catch (error: Exception) {
            runCatching { localRecorder.release() }
            throw error
        }
    }

    override fun finish(): RecordingFinishResult {
        val localRecorder = synchronized(lock) {
            recorder.also { recorder = null }
        }
        if (localRecorder != null) {
            val stopResult = runCatching { localRecorder.stop() }
            runCatching { localRecorder.release() }
            stopResult.exceptionOrNull()?.let { return RecordingFinishResult.Failure(it) }
        }
        return RecordingFinishResult.Success(output.length())
    }

    override fun discard() {
        val localRecorder = synchronized(lock) {
            recorder.also { recorder = null }
        }
        if (localRecorder != null) {
            runCatching { localRecorder.stop() }
            runCatching { localRecorder.release() }
        }
        output.delete()
    }

    override fun silenced(): SilencedFact =
        synchronized(lock) {
            val sdkInt = Build.VERSION.SDK_INT
            val localRecorder = recorder
            if (Build.VERSION.SDK_INT < CLIENT_SILENCED_CONFIGURATION_API || localRecorder == null) {
                SilencedFact.UNKNOWN
            } else {
                // Do not use the device-wide microphone-mute getter: it does not report this recorder's client-silenced state.
                val configuration = localRecorder.activeRecordingConfiguration
                silencedFactOf(
                    sdkInt = sdkInt,
                    hasActiveConfiguration = configuration != null,
                    clientSilenced = configuration?.isClientSilenced == true,
                )
            }
        }
}

internal fun silencedFactOf(
    sdkInt: Int,
    hasActiveConfiguration: Boolean,
    clientSilenced: Boolean,
): SilencedFact =
    when {
        sdkInt < CLIENT_SILENCED_CONFIGURATION_API -> SilencedFact.UNKNOWN
        !hasActiveConfiguration -> SilencedFact.UNKNOWN
        clientSilenced -> SilencedFact.SILENCED
        else -> SilencedFact.NOT_SILENCED
    }

private const val CLIENT_SILENCED_CONFIGURATION_API = 29
