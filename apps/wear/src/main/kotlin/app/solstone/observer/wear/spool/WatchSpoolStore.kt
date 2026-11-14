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
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.time.ZoneOffset
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
        private class SharedState {
            val lock = Any()
            val recovering = mutableSetOf<String>()

            // The live session as this process last published, closed or read it; null until known.
            var liveSession: LiveSession? = null

            // The session whose recording clock is already durable in its metadata.
            var clockWrittenFor: String? = null
        }
        private class LiveSession(val id: String?)
        private val states = ConcurrentHashMap<String, SharedState>()
    }

    constructor(context: Context) : this(
        File(context.filesDir, "wear_spool"),
        AndroidAacAdtsRemuxer(),
        PROCESS_GENERATION,
    )

    private val shared = states.computeIfAbsent(spoolDir.canonicalPath) { SharedState() }

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
    )

    fun readLiveSessionId(): String? {
        return synchronized(shared.lock) {
            shared.liveSession?.let { return it.id }
            val id = if (!liveSessionFile.exists()) {
                null
            } else {
                try {
                    liveSessionFile.readText(Charsets.UTF_8).trim().ifEmpty { null }
                } catch (_: Exception) {
                    return null
                }
            }
            shared.liveSession = LiveSession(id)
            id
        }
    }

    fun closeLiveSession() {
        return synchronized(shared.lock) {
            if (liveSessionFile.exists()) {
                shared.liveSession = null
                liveSessionFile.delete()
                syncDirectory(spoolDir)
            }
            if (!liveSessionFile.exists()) shared.liveSession = LiveSession(null)
        }
    }

    private fun publishLiveSession(sessionId: String) {
        shared.liveSession = null
        atomicWrite(liveSessionFile, sessionId.toByteArray(Charsets.UTF_8))
        shared.liveSession = LiveSession(sessionId)
    }

    fun startOpenSession(zoneId: ZoneId = ZoneId.systemDefault()): String {
        return synchronized(shared.lock) {
            sessionsDir.mkdirs()
            val sessionId = UUID.randomUUID().toString()
            val sessionDir = File(sessionsDir, ".creating_$sessionId")
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
            )
            writeSessionMeta(sessionDir, meta)
            if (!sessionDir.renameTo(File(sessionsDir, sessionId))) throw IOException("Session publication failed")
            syncDirectory(sessionsDir)
            publishLiveSession(sessionId)
            return sessionId
        }
    }

    fun rotateOpenSession(zoneId: ZoneId = ZoneId.systemDefault()): String {
        return synchronized(shared.lock) {
            val previousId = readLiveSessionId() ?: throw IOException("Missing active session")
            startOpenSession(zoneId)
            return previousId
        }
    }

    fun retireSession(sessionId: String): Boolean {
        return synchronized(shared.lock) {
            val currentLive = readLiveSessionId()
            if (sessionId == currentLive) {
                return false
            }
            val dirs = listOf(File(sessionsDir, sessionId), File(sessionsDir, "recovering_$sessionId"))
            val removed = dirs.all { !it.exists() || it.deleteRecursively() }
            if (sessionsDir.exists()) syncDirectory(sessionsDir)
            return removed
        }
    }

    fun appendOpenFrame(frame: TimestampedAudioFrame, zoneId: ZoneId = ZoneId.systemDefault()) {
        return synchronized(shared.lock) {
            val liveId = readLiveSessionId() ?: startOpenSession(zoneId)
            val sessionDir = File(sessionsDir, liveId)
            if (!sessionDir.exists()) {
                sessionDir.mkdirs()
                if (shared.clockWrittenFor == liveId) shared.clockWrittenFor = null
            }
            val framesFile = File(sessionDir, "raw_frames.adts")
            FileOutputStream(framesFile, true).use { fos ->
                fos.write(frame.data)
                fos.fd.sync()
            }

            // Recovery reads only the recording clock from the metadata (first frame time, zone,
            // offset, owner) and measures the length from the frames file itself, so once the
            // first frame's clock is durable, later frames have nothing to add to it.
            if (shared.clockWrittenFor == liveId) return

            val currentMeta = readSessionMeta(sessionDir) ?: SessionMeta(
                sessionId = liveId,
                ownerGeneration = processGeneration,
                unclean = true,
                firstFrameEpochMs = null,
                zoneId = zoneId.id,
                utcOffsetSeconds = zoneId.rules.getOffset(Instant.ofEpochMilli(frame.timestampEpochMs)).totalSeconds,
            )

            val firstEpoch = currentMeta.firstFrameEpochMs ?: frame.timestampEpochMs
            val offset = currentMeta.utcOffsetSeconds
                ?: zoneId.rules.getOffset(Instant.ofEpochMilli(firstEpoch)).totalSeconds

            writeSessionMeta(
                sessionDir,
                currentMeta.copy(
                    firstFrameEpochMs = firstEpoch,
                    zoneId = if (currentMeta.firstFrameEpochMs == null) zoneId.id else currentMeta.zoneId,
                    utcOffsetSeconds = if (currentMeta.firstFrameEpochMs == null) {
                        zoneId.rules.getOffset(Instant.ofEpochMilli(firstEpoch)).totalSeconds
                    } else offset,
                ),
            )
            shared.clockWrittenFor = liveId
        }
    }

    fun recoverAbandonedSessions() {
        if (!sessionsDir.exists()) return

        val candidateDirs = synchronized(shared.lock) { sessionsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } } ?: return
        for (dir in candidateDirs) {
            val dirName = dir.name
            val isAlreadyClaimed = dirName.startsWith("recovering_")
            val sessionId = if (isAlreadyClaimed) dirName.removePrefix("recovering_") else dirName

            val recoveringDir = synchronized(shared.lock) {
                if (sessionId in shared.recovering) return@synchronized null
                val meta = readSessionMeta(dir)
                if (!isAlreadyClaimed && meta?.ownerGeneration == processGeneration) return@synchronized null
                val claimed = if (isAlreadyClaimed) dir else File(sessionsDir, "recovering_$sessionId").also {
                    if (!dir.renameTo(it)) return@synchronized null
                    syncDirectory(sessionsDir)
                }
                if (readLiveSessionId() == sessionId) closeLiveSession()
                shared.recovering.add(sessionId)
                claimed
            } ?: continue

            try {
                val existingBundle = File(spoolDir, "bundle_$sessionId.bin")
                if (existingBundle.isFile && getStagedBundle(existingBundle)?.bundleId == sessionId) {
                    retireSession(sessionId)
                    continue
                }

                val meta = readSessionMeta(recoveringDir)
                val framesFile = File(recoveringDir, "raw_frames.adts")
                val validFrames = parseValidAdtsFrames(framesFile)

                processLossDetected = true
                if (validFrames.isEmpty()) {
                    recordFailure(sessionId, "process_loss")
                    continue
                }
                val firstEpoch = meta?.firstFrameEpochMs ?: throw IOException("Missing recording clock")
                val offset = meta.utcOffsetSeconds ?: throw IOException("Missing recording offset")
                val originalZone = meta.zoneId
                ZoneId.of(originalZone)
                val endEpoch = firstEpoch + validFrames.size * 64L
                val keys = wireKeys(firstEpoch, endEpoch, ZoneOffset.ofTotalSeconds(offset))

                val bundleId = sessionId
                val header = CourierBundleHeader(
                    protocolVersion = WatchCourierProtocol.PROTOCOL_VERSION,
                    bundleId = bundleId,
                    day = keys.day,
                    segment = keys.segment,
                    startEpochMs = firstEpoch,
                    endEpochMs = endEpoch,
                    zoneId = originalZone,
                    utcOffsetSeconds = offset,
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
                    sealSessionBundle(bundleId, baos.toByteArray())
                } else {
                    recordFailure(sessionId, "remux_failed")
                }
            } catch (_: Exception) {
                processLossDetected = true
                runCatching { recordFailure(sessionId, "recovery_failed") }
            } finally {
                synchronized(shared.lock) { shared.recovering.remove(sessionId) }
            }
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
            if (!temp.renameTo(target)) throw IOException("Atomic publication failed")
            syncDirectory(target.parentFile!!)
        } finally {
            temp.delete()
        }
    }

    private fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun writeSessionMeta(dir: File, meta: SessionMeta) {
        val metaFile = File(dir, "session_meta.json")
        val json = """{"sessionId":"${meta.sessionId}","ownerGeneration":"${meta.ownerGeneration}","unclean":${meta.unclean},"firstFrameEpochMs":${meta.firstFrameEpochMs},"zoneId":"${meta.zoneId}","utcOffsetSeconds":${meta.utcOffsetSeconds}}"""
        atomicWrite(metaFile, json.toByteArray(Charsets.UTF_8))
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
            SessionMeta(
                sessionId = sessionId,
                ownerGeneration = ownerGeneration,
                unclean = unclean,
                firstFrameEpochMs = firstEpoch,
                zoneId = zoneId,
                utcOffsetSeconds = utcOffset,
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

    /**
     * What the watch face shows about held material: how many sealed bundles wait and whether a
     * loss is recorded. Read from one directory listing, without opening a bundle or waiting on a
     * frame being written, because the face asks every second.
     */
    fun heldSummary(): HeldSummary {
        val files = spoolDir.listFiles { f ->
            (f.name.startsWith("bundle_") && f.name.endsWith(".bin") || f.name.startsWith("loss_") && f.name.endsWith(".marker")) && f.isFile
        }.orEmpty()
        return HeldSummary(
            bundles = files.count { it.name.startsWith("bundle_") },
            lostMarker = files.any { it.name.startsWith("loss_") },
        )
    }

    data class HeldSummary(val bundles: Int, val lostMarker: Boolean)

    fun storeBundle(bundleId: String, bundleBytes: ByteArray): File = synchronized(shared.lock) {
        require(bundleId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
        val target = File(spoolDir, "bundle_$bundleId.bin")
        atomicWrite(target, bundleBytes)
        target
    }

    fun sealSessionBundle(sessionId: String, bundleBytes: ByteArray): File = synchronized(shared.lock) {
        val target = storeBundle(sessionId, bundleBytes)
        if (!retireSession(sessionId)) throw IOException("Captured session is still active")
        target
    }

    fun recordFailure(sessionId: String, reason: String) = synchronized(shared.lock) {
        atomicWrite(File(spoolDir, "loss_$sessionId.marker"), (reason + "\n").toByteArray())
        for (dir in listOf(File(sessionsDir, sessionId), File(sessionsDir, "recovering_$sessionId"))) {
            val meta = readSessionMeta(dir) ?: continue
            if (readLiveSessionId() != sessionId) writeSessionMeta(dir, meta.copy(ownerGeneration = ""))
        }
    }

    fun listPendingFiles(): List<File> = synchronized(shared.lock) {
        spoolDir.listFiles { f -> f.isFile && f.name.startsWith("bundle_") && f.name.endsWith(".bin") }
            ?.sortedWith(compareBy<File>({ recordingStart(it) }, { it.name }))
            .orEmpty()
    }

    private fun recordingStart(file: File): Long = runCatching {
        file.inputStream().use { input ->
            when (val header = WatchCourierCodec.inspectHeader(input)) {
                is CodecResult.Success -> header.value.startEpochMs
                else -> Long.MIN_VALUE
            }
        }
    }.getOrDefault(Long.MIN_VALUE)

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
        listPendingFiles().map { file ->
            val staged = getStagedBundle(file) ?: throw IOException("Saved watch bundle is unreadable")
            file to staged
        }

    fun deleteBundle(bundleFile: File, receipt: WatchBundleReceipt): Boolean {
        return synchronized(shared.lock) {
            val staged = getStagedBundle(bundleFile) ?: return false
            if (staged.bundleId in shared.recovering) return false
            return if (WatchBundleReceiptRule.mayDelete(staged, receipt) && retireSession(staged.bundleId)) {
                val deleted = bundleFile.delete()
                if (deleted) {
                    File(spoolDir, "loss_${staged.bundleId}.marker").delete()
                    syncDirectory(spoolDir)
                }
                deleted
            } else {
                false
            }
        }
    }
}
