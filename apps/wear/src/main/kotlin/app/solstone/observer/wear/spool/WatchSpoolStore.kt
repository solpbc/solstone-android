// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.wear.spool

import android.content.Context
import app.solstone.core.segment.wireKeys
import app.solstone.core.watch.courier.CodecResult
import app.solstone.core.watch.courier.CourierBundleHeader
import app.solstone.core.watch.courier.CourierFilePayload
import app.solstone.core.watch.courier.StagedWatchBundle
import app.solstone.core.watch.courier.TimestampedAudioFrame
import app.solstone.core.watch.courier.WatchBundleReceipt
import app.solstone.core.watch.courier.WatchBundleReceiptRule
import app.solstone.core.watch.courier.WatchCourierCodec
import app.solstone.core.watch.courier.WatchCourierProtocol
import app.solstone.platform.audio.AacAdtsRemuxer
import app.solstone.platform.audio.AndroidAacAdtsRemuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class WatchSpoolStore(
    private val spoolDir: File,
    private val remuxer: AacAdtsRemuxer? = null,
    val processGeneration: String = PROCESS_GENERATION,
) {
    companion object {
        val PROCESS_GENERATION: String = UUID.randomUUID().toString()
    }

    constructor(context: Context) : this(
        File(context.filesDir, "wear_spool"),
        AndroidAacAdtsRemuxer(),
        PROCESS_GENERATION,
    )

    private val sessionsDir = File(spoolDir, "sessions")
    private val liveSessionFile = File(spoolDir, "live_session")

    var processLossDetected: Boolean = false
        private set

    init {
        spoolDir.mkdirs()
    }

    data class SessionMeta(
        val sessionId: String,
        val ownerGeneration: String,
        val unclean: Boolean,
        val firstFrameEpochMs: Long?,
        val zoneId: String,
        val utcOffsetSeconds: Int?,
        val lastFrameEpochMs: Long?,
        val lastFrameDurationMs: Long?,
        val frameCount: Int,
    )

    @Synchronized
    fun readLiveSessionId(): String? {
        if (!liveSessionFile.exists()) return null
        return try {
            liveSessionFile.readText(Charsets.UTF_8).trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun closeLiveSession() {
        if (liveSessionFile.exists()) {
            liveSessionFile.delete()
        }
    }

    private fun publishLiveSession(sessionId: String) {
        val temp = File(spoolDir, "tmp_live_session_${UUID.randomUUID()}")
        FileOutputStream(temp).use { fos ->
            fos.write(sessionId.toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        if (!temp.renameTo(liveSessionFile)) {
            temp.copyTo(liveSessionFile, overwrite = true)
            temp.delete()
        }
    }

    @Synchronized
    fun startOpenSession(zoneId: ZoneId = ZoneId.systemDefault()): String {
        sessionsDir.mkdirs()
        val sessionId = UUID.randomUUID().toString()
        val sessionDir = File(sessionsDir, sessionId)
        sessionDir.mkdirs()
        val uncleanMarker = File(sessionDir, "unclean")
        FileOutputStream(uncleanMarker).use { fos ->
            fos.write("unclean\n".toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        val offset = zoneId.rules.getOffset(Instant.now()).totalSeconds
        val meta = SessionMeta(
            sessionId = sessionId,
            ownerGeneration = processGeneration,
            unclean = true,
            firstFrameEpochMs = null,
            zoneId = zoneId.id,
            utcOffsetSeconds = offset,
            lastFrameEpochMs = null,
            lastFrameDurationMs = null,
            frameCount = 0,
        )
        writeSessionMeta(sessionDir, meta)
        publishLiveSession(sessionId)
        return sessionId
    }

    @Synchronized
    fun rotateOpenSession(zoneId: ZoneId = ZoneId.systemDefault()): String {
        sessionsDir.mkdirs()
        val previousId = readLiveSessionId() ?: startOpenSession(zoneId)
        val nextId = UUID.randomUUID().toString()
        val nextDir = File(sessionsDir, nextId)
        nextDir.mkdirs()
        val uncleanMarker = File(nextDir, "unclean")
        FileOutputStream(uncleanMarker).use { fos ->
            fos.write("unclean\n".toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        val offset = zoneId.rules.getOffset(Instant.now()).totalSeconds
        val nextMeta = SessionMeta(
            sessionId = nextId,
            ownerGeneration = processGeneration,
            unclean = true,
            firstFrameEpochMs = null,
            zoneId = zoneId.id,
            utcOffsetSeconds = offset,
            lastFrameEpochMs = null,
            lastFrameDurationMs = null,
            frameCount = 0,
        )
        writeSessionMeta(nextDir, nextMeta)
        publishLiveSession(nextId)
        return previousId
    }

    @Synchronized
    fun retireSession(sessionId: String): Boolean {
        val currentLive = readLiveSessionId()
        if (sessionId == currentLive) {
            return false
        }
        val sessionDir = File(sessionsDir, sessionId)
        if (!sessionDir.exists()) return true
        return sessionDir.deleteRecursively()
    }

    @Synchronized
    fun appendOpenFrame(frame: TimestampedAudioFrame, zoneId: ZoneId = ZoneId.systemDefault()) {
        val liveId = readLiveSessionId() ?: startOpenSession(zoneId)
        val sessionDir = File(sessionsDir, liveId)
        if (!sessionDir.exists()) {
            sessionDir.mkdirs()
        }
        val framesFile = File(sessionDir, "raw_frames.adts")
        FileOutputStream(framesFile, true).use { fos ->
            fos.write(frame.data)
            fos.fd.sync()
        }

        val currentMeta = readSessionMeta(sessionDir) ?: SessionMeta(
            sessionId = liveId,
            ownerGeneration = processGeneration,
            unclean = true,
            firstFrameEpochMs = null,
            zoneId = zoneId.id,
            utcOffsetSeconds = zoneId.rules.getOffset(Instant.ofEpochMilli(frame.timestampEpochMs)).totalSeconds,
            lastFrameEpochMs = null,
            lastFrameDurationMs = null,
            frameCount = 0,
        )

        val firstEpoch = currentMeta.firstFrameEpochMs ?: frame.timestampEpochMs
        val offset = currentMeta.utcOffsetSeconds
            ?: zoneId.rules.getOffset(Instant.ofEpochMilli(firstEpoch)).totalSeconds

        writeSessionMeta(
            sessionDir,
            currentMeta.copy(
                firstFrameEpochMs = firstEpoch,
                zoneId = currentMeta.zoneId.ifEmpty { zoneId.id },
                utcOffsetSeconds = offset,
                lastFrameEpochMs = frame.timestampEpochMs,
                lastFrameDurationMs = frame.durationMs,
                frameCount = currentMeta.frameCount + 1,
            ),
        )
    }

    @Synchronized
    fun recoverAbandonedSessions() {
        if (!sessionsDir.exists()) return

        val candidateDirs = sessionsDir.listFiles { f -> f.isDirectory } ?: return
        for (dir in candidateDirs) {
            val dirName = dir.name
            val isAlreadyClaimed = dirName.startsWith("recovering_")
            val sessionId = if (isAlreadyClaimed) dirName.removePrefix("recovering_") else dirName

            val recoveringDir = if (isAlreadyClaimed) {
                dir
            } else {
                val meta = readSessionMeta(dir)
                if (meta != null && meta.ownerGeneration == processGeneration) {
                    continue
                }
                val claimTarget = File(sessionsDir, "recovering_$sessionId")
                if (!dir.renameTo(claimTarget)) {
                    continue
                }
                claimTarget
            }

            try {
                val existingBundle = File(spoolDir, "bundle_$sessionId.bin")
                if (existingBundle.exists()) {
                    recoveringDir.deleteRecursively()
                    continue
                }

                val meta = readSessionMeta(recoveringDir)
                val framesFile = File(recoveringDir, "raw_frames.adts")
                val validFrames = parseValidAdtsFrames(framesFile)

                if (validFrames.isEmpty()) {
                    processLossDetected = true
                    val lossEpoch = meta?.firstFrameEpochMs ?: System.currentTimeMillis()
                    val lossMarker = File(spoolDir, "loss_$lossEpoch.marker")
                    FileOutputStream(lossMarker).use { fos ->
                        fos.write("process_loss\n".toByteArray(Charsets.UTF_8))
                        fos.fd.sync()
                    }
                    recoveringDir.deleteRecursively()
                    continue
                }

                processLossDetected = true
                val firstEpoch = meta?.firstFrameEpochMs ?: System.currentTimeMillis()
                val totalValidDurationMs = validFrames.size * 64L
                val endEpoch = firstEpoch + totalValidDurationMs
                val zone = try {
                    ZoneId.of(meta?.zoneId ?: ZoneId.systemDefault().id)
                } catch (_: Exception) {
                    ZoneId.systemDefault()
                }
                val keys = wireKeys(firstEpoch, endEpoch, zone)

                val bundleId = sessionId
                val header = CourierBundleHeader(
                    protocolVersion = WatchCourierProtocol.PROTOCOL_VERSION,
                    bundleId = bundleId,
                    day = keys.day,
                    segment = keys.segment,
                    startEpochMs = firstEpoch,
                    endEpochMs = endEpoch,
                    zoneId = keys.zoneId,
                    utcOffsetSeconds = keys.utcOffsetSeconds,
                    sealGeneration = 1L,
                )

                val rawAudioBytes = ByteArrayOutputStream().apply {
                    for (f in validFrames) {
                        write(f)
                    }
                }.toByteArray()

                var remuxSuccess = false
                var audioBytes = rawAudioBytes
                if (remuxer != null) {
                    val stagingDir = File(spoolDir, "recovery_staging").apply { mkdirs() }
                    val tempAdts = File(stagingDir, "recovery_$bundleId.adts")
                    val tempM4a = File(stagingDir, "recovery_$bundleId.m4a")
                    try {
                        FileOutputStream(tempAdts).use { fos ->
                            fos.write(rawAudioBytes)
                            fos.fd.sync()
                        }
                        remuxer.remux(tempAdts, tempM4a)
                        if (tempM4a.exists() && tempM4a.length() > 0) {
                            audioBytes = tempM4a.readBytes()
                            remuxSuccess = true
                        }
                    } catch (_: Exception) {
                        remuxSuccess = false
                    } finally {
                        tempAdts.delete()
                        tempM4a.delete()
                        stagingDir.delete()
                    }
                }

                if (remuxSuccess) {
                    val files = listOf(
                        CourierFilePayload(
                            name = WatchCourierProtocol.FILE_NAME_AUDIO,
                            mediaType = WatchCourierProtocol.MEDIA_TYPE_AUDIO,
                            sha256 = WatchCourierCodec.sha256Hex(audioBytes),
                            byteSize = audioBytes.size.toLong(),
                            bytes = audioBytes,
                        ),
                    )
                    val baos = ByteArrayOutputStream()
                    WatchCourierCodec.encodeBundle(header, files, baos)
                    storeBundle(bundleId, baos.toByteArray())
                } else {
                    val failedAdts = File(spoolDir, "failed_remux_$firstEpoch.adts")
                    FileOutputStream(failedAdts).use { fos ->
                        fos.write(rawAudioBytes)
                        fos.fd.sync()
                    }
                    val lossMarker = File(spoolDir, "loss_$firstEpoch.marker")
                    FileOutputStream(lossMarker).use { fos ->
                        fos.write("remux_failed\n".toByteArray(Charsets.UTF_8))
                        fos.fd.sync()
                    }
                }
            } finally {
                recoveringDir.deleteRecursively()
            }
        }
    }

    private fun writeSessionMeta(dir: File, meta: SessionMeta) {
        val metaFile = File(dir, "session_meta.json")
        val json = """{"sessionId":"${meta.sessionId}","ownerGeneration":"${meta.ownerGeneration}","unclean":${meta.unclean},"firstFrameEpochMs":${meta.firstFrameEpochMs},"zoneId":"${meta.zoneId}","utcOffsetSeconds":${meta.utcOffsetSeconds},"lastFrameEpochMs":${meta.lastFrameEpochMs},"lastFrameDurationMs":${meta.lastFrameDurationMs},"frameCount":${meta.frameCount}}"""
        FileOutputStream(metaFile).use { fos ->
            fos.write(json.toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
    }

    private fun readSessionMeta(dir: File): SessionMeta? {
        val metaFile = File(dir, "session_meta.json")
        if (!metaFile.exists()) return null
        return try {
            val text = metaFile.readText(Charsets.UTF_8)
            val sessionId = Regex(""""sessionId":\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: dir.name
            val ownerGeneration = Regex(""""ownerGeneration":\s*"([^"]+)"""").find(text)?.groupValues?.get(1).orEmpty()
            val unclean = Regex(""""unclean":\s*(true|false)""").find(text)?.groupValues?.get(1)?.toBoolean() ?: true
            val firstEpoch = Regex(""""firstFrameEpochMs":\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
            val zoneId = Regex(""""zoneId":\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: ZoneId.systemDefault().id
            val utcOffset = Regex(""""utcOffsetSeconds":\s*(-?\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            val lastEpoch = Regex(""""lastFrameEpochMs":\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
            val lastDuration = Regex(""""lastFrameDurationMs":\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
            val frameCount = Regex(""""frameCount":\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            SessionMeta(
                sessionId = sessionId,
                ownerGeneration = ownerGeneration,
                unclean = unclean,
                firstFrameEpochMs = firstEpoch,
                zoneId = zoneId,
                utcOffsetSeconds = utcOffset,
                lastFrameEpochMs = lastEpoch,
                lastFrameDurationMs = lastDuration,
                frameCount = frameCount,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseValidAdtsFrames(file: File): List<ByteArray> {
        if (!file.exists() || file.length() < 7) return emptyList()
        val bytes = file.readBytes()
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset + 7 <= bytes.size) {
            val b0 = bytes[offset].toInt() and 0xFF
            val b1 = bytes[offset + 1].toInt() and 0xFF
            if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) {
                offset++
                continue
            }
            val b3 = bytes[offset + 3].toInt() and 0xFF
            val b4 = bytes[offset + 4].toInt() and 0xFF
            val b5 = bytes[offset + 5].toInt() and 0xFF
            val frameLength = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 and 0xE0) ushr 5)
            if (frameLength < 7 || frameLength > 8192) {
                offset++
                continue
            }
            if (offset + frameLength > bytes.size) {
                // Truncated trailing bytes: stop and discard truncated frame
                break
            }
            frames.add(bytes.copyOfRange(offset, offset + frameLength))
            offset += frameLength
        }
        return frames
    }

    fun listLostMarkers(): List<File> =
        spoolDir.listFiles { f -> f.isFile && f.name.startsWith("loss_") && f.name.endsWith(".marker") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()

    fun hasLostMarker(): Boolean = listLostMarkers().isNotEmpty()

    fun storeBundle(bundleId: String, bundleBytes: ByteArray): File {
        val temp = File(spoolDir, "tmp_$bundleId.bin")
        val target = File(spoolDir, "bundle_$bundleId.bin")
        FileOutputStream(temp).use { fos ->
            fos.write(bundleBytes)
            fos.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    fun listPendingFiles(): List<File> =
        spoolDir.listFiles { f -> f.isFile && f.name.startsWith("bundle_") && f.name.endsWith(".bin") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()

    fun getStagedBundle(bundleFile: File): StagedWatchBundle? {
        if (!bundleFile.exists()) return null
        return try {
            val bytes = bundleFile.readBytes()
            val decoded = WatchCourierCodec.decodeBundle(bytes.inputStream())
            when (decoded) {
                is CodecResult.Success -> {
                    val header = decoded.value.header
                    StagedWatchBundle(
                        bundleId = header.bundleId,
                        sha256 = decoded.value.bundleSha256,
                        sealGeneration = header.sealGeneration,
                    )
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun listStagedBundles(): List<Pair<File, StagedWatchBundle>> =
        listPendingFiles().mapNotNull { file ->
            getStagedBundle(file)?.let { staged -> file to staged }
        }

    fun deleteBundle(bundleFile: File, receipt: WatchBundleReceipt): Boolean {
        val staged = getStagedBundle(bundleFile) ?: return false
        return if (WatchBundleReceiptRule.mayDelete(staged, receipt)) {
            bundleFile.delete()
        } else {
            false
        }
    }
}
