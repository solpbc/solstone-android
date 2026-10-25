// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.audio

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.segment.sha256
import app.solstone.core.segment.wireKeys
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.GapNoticeStore
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.SealedSegmentSink
import app.solstone.core.spool.adtsFileExisted
import app.solstone.core.spool.gapLossFactExists
import app.solstone.core.spool.legacyMaterial
import app.solstone.core.spool.payloadBytesExisted
import app.solstone.core.spool.writeGapLossFact
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.ZoneId

sealed interface AudioCommitStatus {
    data object CommittedMatch : AudioCommitStatus
    data class IdentityCollisionDifferentBytes(val boundsEqual: Boolean) : AudioCommitStatus
    data object CustodyUnproven : AudioCommitStatus
    data object NotFound : AudioCommitStatus
}

data class AudioSpoolFact(
    val segmentId: String,
    val sha256: String,
    val captureStartEpochMs: Long,
    val captureEndEpochMs: Long,
    val sourceId: String,
    val name: String,
    val spoolPayloadExists: Boolean,
    val spoolPayloadShaMatches: Boolean,
    val zoneId: String? = null,
    val confirmedUploaded: Boolean = false,
    val spoolCustodyDurable: Boolean = false,
)

fun classifyAudioAttempt(
    sha256: String,
    captureStartEpochMs: Long,
    captureEndEpochMs: Long,
    sourceId: String,
    name: String,
    day: String,
    stream: String,
    segmentLeaf: String,
    facts: List<AudioSpoolFact>,
): AudioCommitStatus {
    val wireId = "$day/$stream/$segmentLeaf"
    var committedMatch = false
    for (fact in facts) {
        if (fact.sourceId != sourceId || fact.name != name) continue
        if (fact.sha256 == sha256 && fact.captureStartEpochMs == captureStartEpochMs && fact.captureEndEpochMs == captureEndEpochMs) {
            if (!fact.confirmedUploaded && fact.spoolPayloadExists && !fact.spoolPayloadShaMatches) {
                return AudioCommitStatus.IdentityCollisionDifferentBytes(boundsEqual = true)
            }
            if (fact.confirmedUploaded || (fact.spoolPayloadExists && fact.spoolPayloadShaMatches && fact.spoolCustodyDurable)) {
                committedMatch = true
            } else {
                return AudioCommitStatus.CustodyUnproven
            }
        } else if (fact.sha256 != sha256) {
            val boundsEqual = fact.captureStartEpochMs == captureStartEpochMs && fact.captureEndEpochMs == captureEndEpochMs
            if (fact.segmentId == wireId || boundsEqual) {
                return AudioCommitStatus.IdentityCollisionDifferentBytes(boundsEqual = boundsEqual)
            }
        }
    }
    return if (committedMatch) AudioCommitStatus.CommittedMatch else AudioCommitStatus.NotFound
}

fun legacyCacheAlreadyDelivered(sha256: String, facts: List<AudioSpoolFact>): Boolean {
    return facts.any { fact ->
        !fact.zoneId.isNullOrBlank() &&
            fact.sourceId == AudioContinuousSourceEngine.SOURCE_ID &&
            fact.name == AudioContinuousSourceEngine.PAYLOAD_NAME &&
            fact.captureEndEpochMs > fact.captureStartEpochMs &&
            fact.sha256 == sha256 &&
            fact.spoolPayloadExists &&
            fact.spoolPayloadShaMatches && fact.spoolCustodyDurable
    }
}

fun interface AudioSpoolLookup {
    fun findStatus(
        sha256: String,
        captureStartEpochMs: Long,
        captureEndEpochMs: Long,
        sourceId: String,
        name: String,
        day: String,
        stream: String,
        segmentLeaf: String,
    ): AudioCommitStatus

    fun legacyManifestCoordinatesMatch(sha256: String): Boolean = false
}

class AudioRecoveryManager(
    private val audioSourceDir: File,
    private val cacheAudioDir: File?,
    private val spoolDir: Path,
    private val occupiedLeaves: (day: String, stream: String) -> Set<String>,
    private val lookup: AudioSpoolLookup,
    private val sealedSink: SealedSegmentSink,
    private val gaps: GapNoticeStore,
    private val remuxer: AacAdtsRemuxer,
    private val isReadableM4aProbe: (File) -> Boolean = { isReadableM4a(it) },
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    fun recover(inFlightIds: Set<String> = emptySet()) {
        recoverAttemptDirectories(inFlightIds)
        recoverLegacyCache()
    }

    private fun recoverAttemptDirectories(inFlightIds: Set<String>) {
        if (!audioSourceDir.exists() || !audioSourceDir.isDirectory) return
        val dirs = audioSourceDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("rec-") } ?: return

        val spoolWriter = FileSpoolWriter(spoolDir, occupiedLeaves = occupiedLeaves)

        for (dir in dirs) {
            val attemptId = dir.name
            if (attemptId in inFlightIds) continue

            try {
                val recordFile = File(dir, "record.txt")
                if (!recordFile.isFile) {
                    keepUndeliverableOrNoBytes(dir, attemptId, null)
                    continue
                }

                val info = AudioRecordInfo.parse(recordFile.readText(Charsets.UTF_8))
                if (info == null) {
                    keepUndeliverableOrNoBytes(dir, attemptId, null)
                    continue
                }

                val adtsFile = File(dir, "capture.adts")
                val m4aFile = File(dir, AudioContinuousSourceEngine.PAYLOAD_NAME)
                val adtsNonEmpty = adtsFile.isFile && adtsFile.length() > 0L
                val m4aNonEmpty = m4aFile.isFile && m4aFile.length() > 0L
                val captured = info.captureStartEpochMs

                val captureEndEpochMs: Long
                if (!adtsNonEmpty && !m4aNonEmpty) {
                    gaps.recordDiagnostic("audio", "no bytes", attemptId, captured)
                    dir.deleteRecursively()
                    continue
                } else if (info.sampleDurationMs != null && info.sampleDurationMs > 0L && m4aNonEmpty && isReadableM4aProbe(m4aFile)) {
                    val openEnd = info.openEndEpochMs ?: info.windowEndEpochMs
                    captureEndEpochMs = minOf(info.captureStartEpochMs + info.sampleDurationMs, openEnd)
                } else if (adtsNonEmpty) {
                    val remux = try {
                        remuxer.remux(adtsFile, m4aFile)
                    } catch (_: Throwable) {
                        gaps.recordDiagnostic("audio", "remux failed", attemptId, captured)
                        continue
                    }
                    if (remux.sampleDurationMs <= 0L || remux.outputBytes <= 0L) {
                        deleteUndeliverableOrEmpty(dir, attemptId, captured)
                        continue
                    }
                    val updatedInfo = info.copy(sampleDurationMs = remux.sampleDurationMs)
                    if (!AudioContinuousSourceEngine.writeAndForceRecordTxt(recordFile, updatedInfo)) {
                        gaps.recordDiagnostic("audio", "record not written", attemptId, captured)
                        continue
                    }
                    val openEnd = info.openEndEpochMs ?: info.windowEndEpochMs
                    captureEndEpochMs = minOf(info.captureStartEpochMs + remux.sampleDurationMs, openEnd)
                } else {
                    if (audioAttemptExisted(dir)) keepLoss(dir, attemptId, captured)
                    else gaps.recordDiagnostic("audio", "no bytes", attemptId, captured)
                    continue
                }

                if (captureEndEpochMs <= info.captureStartEpochMs) {
                    if (audioAttemptExisted(dir)) keepLoss(dir, attemptId, captured)
                    else gaps.recordDiagnostic("audio", "no bytes", attemptId, captured)
                    continue
                }

                val m4aSha256 = sha256(m4aFile.toPath())
                val m4aSize = m4aFile.length()
                val baseKeys = wireKeys(info.captureStartEpochMs, captureEndEpochMs, ZoneId.of(info.zoneId))
                val finalWireKeys = baseKeys.copy(utcOffsetSeconds = info.utcOffsetSeconds)

                val status = lookup.findStatus(
                    sha256 = m4aSha256,
                    captureStartEpochMs = info.captureStartEpochMs,
                    captureEndEpochMs = captureEndEpochMs,
                    sourceId = AudioContinuousSourceEngine.SOURCE_ID,
                    name = AudioContinuousSourceEngine.PAYLOAD_NAME,
                    day = finalWireKeys.day,
                    stream = MAIN_STREAM,
                    segmentLeaf = finalWireKeys.segment,
                )

                when (status) {
                    is AudioCommitStatus.CommittedMatch -> {
                        dir.deleteRecursively()
                        continue
                    }
                    is AudioCommitStatus.IdentityCollisionDifferentBytes -> {
                        if (status.boundsEqual) {
                            gaps.recordDiagnostic("audio", "same bounds", attemptId, info.captureStartEpochMs)
                        } else if (audioAttemptExisted(dir)) {
                            keepLoss(dir, attemptId, info.captureStartEpochMs)
                        } else {
                            gaps.recordDiagnostic("audio", "no bytes", attemptId, info.captureStartEpochMs)
                        }
                        continue
                    }
                    is AudioCommitStatus.CustodyUnproven -> {
                        gaps.recordDiagnostic("audio", "custody unproven", attemptId, info.captureStartEpochMs)
                        continue
                    }
                    is AudioCommitStatus.NotFound -> {
                        val payload = SegmentPayload(
                            sourceId = AudioContinuousSourceEngine.SOURCE_ID,
                            ref = PayloadRef(AudioContinuousSourceEngine.PAYLOAD_NAME, AudioContinuousSourceEngine.MEDIA_TYPE, m4aSize, null),
                            captureStartEpochMs = info.captureStartEpochMs,
                            captureEndEpochMs = captureEndEpochMs,
                        )
                        val segment = SealedSegment(
                            stream = MAIN_STREAM,
                            key = SegmentKey(finalWireKeys.day, finalWireKeys.segment),
                            wireKeys = finalWireKeys,
                            payloads = listOf(payload),
                            gaps = emptyList(),
                        )

                        val singleProvider = object : PayloadBytesProvider {
                            override fun open(payload: SegmentPayload): InputStream = m4aFile.inputStream()
                            override fun release(payload: SegmentPayload) {}
                        }

                        val sealResult = try {
                            spoolWriter.seal(segment, singleProvider)
                        } catch (_: Throwable) {
                            gaps.recordDiagnostic("audio", "seal failed", attemptId, info.captureStartEpochMs)
                            continue
                        }

                        try {
                            sealedSink.persistSealed(segment, sealResult, nowProvider())
                        } catch (_: Throwable) {
                            gaps.recordDiagnostic("audio", "seal failed", attemptId, info.captureStartEpochMs)
                            continue
                        }

                        gaps.recordDiagnostic("audio", "delivered", attemptId, info.captureStartEpochMs)
                        dir.deleteRecursively()
                    }
                }
            } catch (_: Exception) {
                gaps.recordDiagnostic("audio", "seal failed", attemptId, null)
                continue
            }
        }
    }

    private fun recoverLegacyCache() {
        val cacheDir = cacheAudioDir ?: return
        if (!cacheDir.exists() || !cacheDir.isDirectory) return
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return

        val legacyDir = File(audioSourceDir, "legacy")

        for (file in files) {
            val fileSha256 = runCatching { sha256(file.toPath()) }.getOrNull() ?: ""

            if (fileSha256.isNotEmpty() && lookup.legacyManifestCoordinatesMatch(fileSha256)) {
                file.delete()
                continue
            }

            val shaKey = if (fileSha256.isNotEmpty()) fileSha256 else "unreadable"
            val factDir = File(legacyDir, shaKey)
            if (gapLossFactExists(factDir.toPath())) {
                file.delete()
                continue
            }
            val hasBytes = file.length() > 0L
            if (!hasBytes) {
                gaps.recordDiagnostic("audio", "no bytes", legacyMaterial(fileSha256), null)
            } else if (!gaps.recordLoss("audio", "undeliverable", legacyMaterial(fileSha256), null)) {
                continue
            }

            factDir.mkdirs()
            var targetFile = File(factDir, file.name)
            var counter = 1
            while (targetFile.exists()) {
                val targetSha = runCatching { sha256(targetFile.toPath()) }.getOrNull()
                if (targetSha == null) {
                    break
                }
                if (fileSha256.isNotEmpty() && targetSha == fileSha256) {
                    break
                }
                targetFile = File(factDir, "${file.name}__$counter")
                counter++
            }

            val durable = if (targetFile.exists()) {
                val targetSha = runCatching { sha256(targetFile.toPath()) }.getOrNull()
                targetSha != null && fileSha256.isNotEmpty() && targetSha == fileSha256 &&
                    forceFileAndParent(targetFile, audioSourceDir)
            } else {
                try {
                    Files.copy(file.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    forceFileAndParent(targetFile, audioSourceDir)
                } catch (_: Exception) {
                    false
                }
            }
            if (!durable) continue
            if (hasBytes && !writeGapLossFact(factDir.toPath())) continue
            file.delete()
        }
    }

    private fun keepUndeliverableOrNoBytes(dir: File, attemptId: String, captured: Long?) {
        if (audioAttemptExisted(dir)) keepLoss(dir, attemptId, captured)
        else gaps.recordDiagnostic("audio", "no bytes", attemptId, captured)
    }

    private fun deleteUndeliverableOrEmpty(dir: File, attemptId: String, captured: Long?) {
        if (audioAttemptExisted(dir)) {
            if (gaps.recordLoss("audio", "undeliverable", attemptId, captured)) dir.deleteRecursively()
        } else {
            gaps.recordDiagnostic("audio", "empty recording", attemptId, captured)
            dir.deleteRecursively()
        }
    }

    /** A kept undeliverable folder gets one loss and a fact. A failed write leaves the folder without a fact. */
    private fun keepLoss(dir: File, attemptId: String, captured: Long?) {
        if (gapLossFactExists(dir.toPath())) return
        if (!gaps.recordLoss("audio", "undeliverable", attemptId, captured)) return
        writeGapLossFact(dir.toPath())
    }

    private companion object {
        fun forceFileAndParent(file: File, durableRoot: File): Boolean {
            val wasInterrupted = Thread.interrupted()
            return try {
                FileChannel.open(file.toPath(), StandardOpenOption.READ).use { it.force(true) }
                var parent = file.parentFile
                while (parent != null) {
                    FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) }
                    if (parent == durableRoot.parentFile) break
                    parent = parent.parentFile
                }
                true
            } catch (_: Exception) {
                false
            } finally {
                if (wasInterrupted) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }
}

internal fun audioAttemptExisted(dir: File): Boolean {
    val m4a = File(dir, AudioContinuousSourceEngine.PAYLOAD_NAME)
    if (m4a.isFile && payloadBytesExisted(m4a.length())) return true
    return adtsFileExisted(File(dir, "capture.adts").toPath())
}

fun isReadableM4a(file: File): Boolean {
    if (!file.exists() || file.length() <= 0L) return false
    return try {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            if (extractor.trackCount <= 0) return false
            extractor.selectTrack(0)
            val buffer = java.nio.ByteBuffer.allocate(1024)
            extractor.readSampleData(buffer, 0) > 0
        } finally {
            runCatching { extractor.release() }
        }
    } catch (_: Exception) {
        false
    }
}
