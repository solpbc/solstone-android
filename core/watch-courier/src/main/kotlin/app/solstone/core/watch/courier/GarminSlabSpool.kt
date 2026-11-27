// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Properties

/**
 * Durable, crash-safe phone-side SLV3 slab spool and segment assembler.
 *
 * Durability invariants:
 * - Every valid slab is written atomically (`tmp` + `fsync` + rename) before any cumulative ACK advances.
 * - A segment's closing slab is never acknowledged until `commitBundle` confirms durable phone custody.
 * - Duplicate retransmissions are idempotent; out-of-order slabs are held without skipping gaps.
 * - Unsealed segments superseded by a newer `segId` (watch reboot/crash) are recovered as partial
 *   segments stamped with their actual elapsed duration rather than dropped or marked full 300s.
 */
class GarminSlabSpool(
    private val rootDir: File,
    private val commitBundle: (DecodedBundle) -> CommitOutcome,
) {
    sealed interface CommitOutcome {
        data object Committed : CommitOutcome
        data object Duplicate : CommitOutcome
        data class Failed(val reason: String) : CommitOutcome
    }

    data class SlabAction(
        val ackSeq: Long,
        val shouldAck: Boolean,
        val isBgOrigin: Boolean,
        val isMorePending: Boolean,
        val watchProtoVer: Int,
        val segmentsCommitted: Int,
        val rejectedReason: String? = null,
    )

    private val lock = Any()

    fun onSlab(deviceId: Long, rawSlab: ByteArray): SlabAction = synchronized(lock) {
        val parsed = GarminSlv3Protocol.parseSlv3(rawSlab)
            ?: return@synchronized SlabAction(-1L, false, false, false, -1, 0, "not_slv3")
        if (!parsed.tagOk) {
            return@synchronized SlabAction(-1L, false, parsed.isBgOrigin, parsed.isMorePending, parsed.protoVer, 0, "tag_mismatch")
        }
        if (parsed.protoVer != GarminSlv3Protocol.PROTO_VERSION) {
            return@synchronized SlabAction(-1L, false, parsed.isBgOrigin, parsed.isMorePending, parsed.protoVer, 0, "version_mismatch")
        }

        val devDir = File(rootDir, "dev_$deviceId")
        ensureDir(devDir)
        val state = readState(devDir)

        // First slab ever seen from this device (or watch Storage reset back to seq 0)
        var committed = 0
        if (state.contiguousAckSeq < 0L) {
            state.contiguousAckSeq = parsed.seq - 1L
        } else if (parsed.seq == 0L && state.contiguousAckSeq > 0L) {
            // Watch Storage was wiped cleanly after a prior session; seal any leftover open segment first
            val (resetCommitted, allSealed) = sealAllOpenSegments(devDir, deviceId, state)
            committed += resetCommitted
            if (!allSealed) {
                return@synchronized SlabAction(
                    ackSeq = state.contiguousAckSeq,
                    shouldAck = false,
                    isBgOrigin = parsed.isBgOrigin,
                    isMorePending = parsed.isMorePending,
                    watchProtoVer = parsed.protoVer,
                    segmentsCommitted = committed,
                    rejectedReason = "prior_segment_commit_failed",
                )
            }
            state.contiguousAckSeq = -1L
        }

        // Duplicate of already-acknowledged slab: return existing cumulative ACK idempotently
        if (parsed.seq <= state.contiguousAckSeq) {
            return@synchronized SlabAction(
                ackSeq = state.contiguousAckSeq,
                shouldAck = parsed.isAckReq && state.contiguousAckSeq >= 0L,
                isBgOrigin = parsed.isBgOrigin,
                isMorePending = parsed.isMorePending,
                watchProtoVer = parsed.protoVer,
                segmentsCommitted = 0,
            )
        }

        // Persist raw slab atomically to disk BEFORE advancing any cursor or ACK
        val segKey = segmentKey(parsed.segId, parsed.segEpochSec)
        val segDir = File(devDir, segKey)
        ensureDir(segDir)
        val slabFile = File(segDir, slabFileName(parsed.seq))
        if (!slabFile.isFile) {
            writeAtomic(slabFile, rawSlab)
        }

        // Advance contiguous watermark strictly in seq order, recovering orphaned pre-crash segments
        // only once the contiguous cursor reaches a newer segment without gaps.
        val (newAck, newlyCommitted) = advanceContiguousAndSeal(devDir, deviceId, state)
        committed += newlyCommitted
        state.contiguousAckSeq = newAck
        writeState(devDir, state)

        SlabAction(
            ackSeq = state.contiguousAckSeq,
            shouldAck = parsed.isAckReq && state.contiguousAckSeq >= 0L,
            isBgOrigin = parsed.isBgOrigin,
            isMorePending = parsed.isMorePending,
            watchProtoVer = parsed.protoVer,
            segmentsCommitted = committed,
        )
    }

    fun currentAckSeq(deviceId: Long): Long = synchronized(lock) {
        val devDir = File(rootDir, "dev_$deviceId")
        if (!devDir.isDirectory) return@synchronized -1L
        readState(devDir).contiguousAckSeq
    }

    private fun advanceContiguousAndSeal(devDir: File, deviceId: Long, state: DeviceState): Pair<Long, Int> {
        // Index all currently staged slabs on disk by seq
        val slabIndex = HashMap<Long, Pair<File, GarminSlv3Protocol.Slv3Slab>>()
        val segDirs = devDir.listFiles { f -> f.isDirectory && f.name.startsWith("seg_") }.orEmpty()
        for (segDir in segDirs) {
            val files = segDir.listFiles { f -> f.isFile && f.name.startsWith("slab_") && f.name.endsWith(".bin") }.orEmpty()
            for (f in files) {
                val parsed = runCatching { GarminSlv3Protocol.parseSlv3(f.readBytes()) }.getOrNull() ?: continue
                if (parsed.tagOk) {
                    slabIndex[parsed.seq] = segDir to parsed
                }
            }
        }

        var cursor = state.contiguousAckSeq
        var committed = 0
        while (true) {
            val nextSeq = cursor + 1L
            val entry = slabIndex[nextSeq] ?: break
            val (segDir, slab) = entry

            // If nextSeq begins or continues a newer segment while older unsealed segment directories
            // contain only slabs <= cursor (proving zero gaps before nextSeq — e.g. watch rebooted
            // mid-segment before FLAG_SEG_END), recover and commit those older segments oldest-first
            // BEFORE advancing cursor into the newer segment.
            val orphanDirs = devDir.listFiles { f -> f.isDirectory && f.name.startsWith("seg_") && f.name != segDir.name }
                .orEmpty()
                .sortedBy { it.name }
            var orphanCommitFailed = false
            for (oldDir in orphanDirs) {
                val oldSlabs = loadSortedSlabs(oldDir)
                if (oldSlabs.isEmpty()) {
                    oldDir.deleteRecursively()
                    continue
                }
                if (oldSlabs.all { it.seq <= cursor }) {
                    val ok = commitSegmentDir(devDir, oldDir, deviceId, maxSeq = cursor, forcedPartial = true)
                    if (!ok) {
                        orphanCommitFailed = true
                        break
                    }
                    committed++
                }
            }
            if (orphanCommitFailed) break

            if (slab.isSegEnd) {
                // Segment closes on nextSeq: commit complete segment BEFORE advancing ACK past nextSeq!
                val ok = commitSegmentDir(devDir, segDir, deviceId, maxSeq = nextSeq, forcedPartial = slab.isSegPartial)
                if (!ok) {
                    // Custody commit failed (e.g., transient storage error): do NOT ACK nextSeq so watch retries
                    break
                }
                committed++
            }
            cursor = nextSeq
        }
        return cursor to committed
    }

    private fun sealAllOpenSegments(devDir: File, deviceId: Long, state: DeviceState): Pair<Int, Boolean> {
        var committed = 0
        val segDirs = devDir.listFiles { f -> f.isDirectory && f.name.startsWith("seg_") }
            .orEmpty()
            .sortedBy { it.name }
        for (segDir in segDirs) {
            val slabs = loadSortedSlabs(segDir)
            if (slabs.isEmpty()) {
                segDir.deleteRecursively()
                continue
            }
            val highestInSeg = slabs.last().seq
            if (!commitSegmentDir(devDir, segDir, deviceId, maxSeq = highestInSeg, forcedPartial = true)) {
                return committed to false
            }
            committed++
            if (highestInSeg > state.contiguousAckSeq) {
                state.contiguousAckSeq = highestInSeg
            }
        }
        return committed to true
    }

    private fun commitSegmentDir(
        devDir: File,
        segDir: File,
        deviceId: Long,
        maxSeq: Long,
        forcedPartial: Boolean,
    ): Boolean {
        val slabs = loadSortedSlabs(segDir).filter { it.seq <= maxSeq }
        if (slabs.isEmpty()) {
            segDir.deleteRecursively()
            return true
        }
        // Verify contiguous seq run within this segment; if a gap exists, seal only contiguous prefix or mark partial
        var hasGap = false
        for (i in 1 until slabs.size) {
            if (slabs[i].seq != slabs[i - 1].seq + 1L) {
                hasGap = true
                break
            }
        }

        val first = slabs.first()
        val last = slabs.last()
        val out = ByteArrayOutputStream(slabs.sumOf { it.payload.size })
        for (s in slabs) {
            out.write(s.payload)
        }
        val audioBytes = out.toByteArray()
        if (audioBytes.isEmpty()) {
            segDir.deleteRecursively()
            return true
        }

        val isPartial = forcedPartial || hasGap || last.isSegPartial || !last.isSegEnd
        val durationSec = if (!isPartial && last.relMs in 295_000L..305_000L) {
            300
        } else {
            ((last.relMs + 2_500L) / 1_000L).toInt().coerceIn(1, 3600)
        }

        val clock = GarminSlv3Protocol.watchLocalSegmentClock(
            segEpochSec = first.segEpochSec,
            tzOffsetMin = first.tzOffsetMin,
            durationSec = durationSec,
        )
        val audioSha = sha256Hex(audioBytes)
        val bundleId = "garmin-$deviceId-${first.segEpochSec}-${first.segId}"
        val filePayload = CourierFilePayload(
            name = GarminSlv3Protocol.FILE_NAME_GARMIN_AUDIO,
            mediaType = GarminSlv3Protocol.MEDIA_TYPE_GARMIN_AUDIO,
            sha256 = audioSha,
            byteSize = audioBytes.size.toLong(),
            bytes = audioBytes,
        )
        val header = CourierBundleHeader(
            protocolVersion = WatchCourierProtocol.PROTOCOL_VERSION,
            bundleId = bundleId,
            day = clock.day,
            segment = clock.segment,
            startEpochMs = clock.startEpochMs,
            endEpochMs = clock.endEpochMs,
            zoneId = clock.zoneId,
            utcOffsetSeconds = clock.utcOffsetSeconds,
            sealGeneration = first.segId.toLong(),
        )
        val bundleSha = sha256Hex("$bundleId:$audioSha".toByteArray(Charsets.UTF_8))
        val decoded = DecodedBundle(
            header = header,
            bundleSha256 = bundleSha,
            files = listOf(filePayload),
        )

        return when (commitBundle(decoded)) {
            CommitOutcome.Committed, CommitOutcome.Duplicate -> {
                segDir.deleteRecursively()
                true
            }
            is CommitOutcome.Failed -> false
        }
    }

    private fun loadSortedSlabs(segDir: File): List<GarminSlv3Protocol.Slv3Slab> {
        val files = segDir.listFiles { f -> f.isFile && f.name.startsWith("slab_") && f.name.endsWith(".bin") }.orEmpty()
        return files.mapNotNull { f ->
            runCatching { GarminSlv3Protocol.parseSlv3(f.readBytes()) }.getOrNull()?.takeIf { it.tagOk }
        }.sortedBy { it.seq }
    }

    private data class DeviceState(
        var contiguousAckSeq: Long = -1L,
    )

    private fun readState(devDir: File): DeviceState {
        val f = File(devDir, "state.properties")
        if (!f.isFile) return DeviceState()
        val p = Properties()
        runCatching { f.inputStream().use { p.load(it) } }
        return DeviceState(
            contiguousAckSeq = p.getProperty("ackSeq")?.toLongOrNull() ?: -1L,
        )
    }

    private fun writeState(devDir: File, state: DeviceState) {
        val p = Properties().apply {
            setProperty("ackSeq", state.contiguousAckSeq.toString())
        }
        val buf = ByteArrayOutputStream()
        p.store(buf, null)
        writeAtomic(File(devDir, "state.properties"), buf.toByteArray())
    }

    private fun segmentKey(segId: Int, segEpochSec: Long): String =
        "seg_%010d_%05d".format(segEpochSec, segId)

    private fun slabFileName(seq: Long): String =
        "slab_%010d.bin".format(seq)

    private fun ensureDir(dir: File) {
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create directory $dir" }
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: error("Missing parent for $target")
        ensureDir(parent)
        val tmp = File(parent, "${target.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.fd.sync()
        }
        check(tmp.renameTo(target)) { "Failed atomic rename $tmp -> $target" }
        runCatching {
            FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
