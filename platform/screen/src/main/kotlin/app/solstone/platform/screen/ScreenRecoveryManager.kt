// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import app.solstone.core.model.SegmentKey
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
import app.solstone.core.spool.gapLossFactExists
import app.solstone.core.spool.payloadBytesExisted
import app.solstone.core.spool.screenIndexHasRealSample
import app.solstone.core.spool.writeGapLossFact
import app.solstone.platform.audio.AudioCommitStatus
import app.solstone.platform.audio.AudioSpoolFact
import app.solstone.platform.audio.AudioSpoolLookup
import app.solstone.platform.audio.classifyAudioAttempt
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.time.ZoneId

class ScreenRecoveryManager(
    private val screenSourceDir: File,
    private val spoolDir: Path,
    private val occupiedLeaves: (day: String, stream: String) -> Set<String>,
    private val lookup: AudioSpoolLookup,
    private val sealedSink: SealedSegmentSink,
    private val gaps: GapNoticeStore,
    private val remuxer: ScreenRemuxer,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val factsProvider: ((day: String, stream: String, segmentLeaf: String, sha256: String) -> List<AudioSpoolFact>)? = null,
) {
    fun recover(inFlightIds: Set<String> = emptySet()) {
        if (!screenSourceDir.exists() || !screenSourceDir.isDirectory) return
        val dirs = screenSourceDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("rec-") } ?: return

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

                var info = ScreenRecordInfo.parse(recordFile.readText())
                if (info == null) {
                    keepUndeliverableOrNoBytes(dir, attemptId, null)
                    continue
                }

                val h264File = File(dir, "capture.h264")
                val indexFile = File(dir, "samples.idx")
                val mp4File = File(dir, ScreenConstants.PAYLOAD_NAME)
                val h264NonEmpty = h264File.isFile && h264File.length() > 0L
                val mp4NonEmpty = mp4File.isFile && mp4File.length() > 0L
                val captured = info.captureStartEpochMs

                val captureEndEpochMs: Long
                if (!h264NonEmpty && !mp4NonEmpty) {
                    gaps.recordDiagnostic("screen", "no bytes", attemptId, captured)
                    dir.deleteRecursively()
                    continue
                } else if (info.sampleDurationMs != null && info.sampleDurationMs > 0L && mp4NonEmpty) {
                    captureEndEpochMs = info.captureBounds(info.sampleDurationMs!!).second
                } else if (h264NonEmpty && indexFile.isFile && indexFile.length() > 0L) {
                    val remuxResult = try {
                        remuxer.remux(dir, h264File, indexFile, recordFile, mp4File)
                    } catch (_: Throwable) {
                        gaps.recordDiagnostic("screen", "remux failed", attemptId, captured)
                        continue
                    }
                    if (remuxResult !is ScreenRemuxResult.Success || remuxResult.sampleDurationMs <= 0L || remuxResult.outputBytes <= 0L) {
                        if (indexFile.isFile && screenIndexHasRealSample(indexFile.readText())) {
                            keepLoss(dir, attemptId, captured)
                        } else {
                            gaps.recordDiagnostic("screen", "no captured frame", attemptId, captured)
                        }
                        continue
                    }
                    info = info.copy(sampleDurationMs = remuxResult.sampleDurationMs)
                    if (!ScreenContinuousSourceEngine.writeAndForceRecordTxt(recordFile, info)) {
                        keepFrameOrNoFrame(dir, attemptId, captured)
                        continue
                    }
                    captureEndEpochMs = info.captureBounds(remuxResult.sampleDurationMs).second
                } else {
                    keepFrameOrNoFrame(dir, attemptId, captured)
                    continue
                }

                val captureStart = info.firstFrameEpochMs ?: info.captureStartEpochMs
                if (captureEndEpochMs <= captureStart) {
                    keepFrameOrNoFrame(dir, attemptId, captured)
                    continue
                }

                val mp4Sha256 = sha256(mp4File.toPath())
                val mp4Size = mp4File.length()

                val baseKeys = wireKeys(captureStart, captureEndEpochMs, ZoneId.of(info.zoneId))
                val finalWireKeys = baseKeys.copy(utcOffsetSeconds = info.utcOffsetSeconds)
                val wireId = "${finalWireKeys.day}/$MAIN_STREAM/${finalWireKeys.segment}"

                val facts = factsProvider?.invoke(finalWireKeys.day, MAIN_STREAM, finalWireKeys.segment, mp4Sha256)
                val status = if (facts != null) {
                    classifyAudioAttempt(
                        sha256 = mp4Sha256,
                        captureStartEpochMs = captureStart,
                        captureEndEpochMs = captureEndEpochMs,
                        sourceId = ScreenConstants.SOURCE_ID,
                        name = ScreenConstants.PAYLOAD_NAME,
                        day = finalWireKeys.day,
                        stream = MAIN_STREAM,
                        segmentLeaf = finalWireKeys.segment,
                        facts = facts,
                    )
                } else {
                    lookup.findStatus(
                        sha256 = mp4Sha256,
                        captureStartEpochMs = captureStart,
                        captureEndEpochMs = captureEndEpochMs,
                        sourceId = ScreenConstants.SOURCE_ID,
                        name = ScreenConstants.PAYLOAD_NAME,
                        day = finalWireKeys.day,
                        stream = MAIN_STREAM,
                        segmentLeaf = finalWireKeys.segment,
                    )
                }

                when (status) {
                    is AudioCommitStatus.CommittedMatch -> {
                        dir.deleteRecursively()
                        continue
                    }
                    is AudioCommitStatus.CustodyUnproven -> {
                        gaps.recordDiagnostic("screen", "custody unproven", attemptId, captureStart)
                        continue
                    }
                    is AudioCommitStatus.IdentityCollisionDifferentBytes -> {
                        val hasSameBoundsMismatch = facts?.any {
                            it.sourceId == ScreenConstants.SOURCE_ID &&
                                it.name == ScreenConstants.PAYLOAD_NAME &&
                                it.sha256 != mp4Sha256 &&
                                it.captureStartEpochMs == captureStart &&
                                it.captureEndEpochMs == captureEndEpochMs
                        } ?: false
                        val hasSameLeafMismatch = facts?.any {
                            it.sourceId == ScreenConstants.SOURCE_ID &&
                                it.name == ScreenConstants.PAYLOAD_NAME &&
                                it.sha256 != mp4Sha256 &&
                                it.segmentId.startsWith("${wireId}__ws${captureStart}")
                        } ?: false
                        if (facts == null || hasSameBoundsMismatch) {
                            gaps.recordDiagnostic("screen", "same bounds", attemptId, captureStart)
                            continue
                        }
                        if (hasSameLeafMismatch) {
                            keepLoss(dir, attemptId, captureStart)
                            continue
                        }
                        // Different bounds only: fall through and seal.
                    }
                    is AudioCommitStatus.NotFound -> {
                        // proceed to seal
                    }
                }

                val payload = SegmentPayload(
                    sourceId = ScreenConstants.SOURCE_ID,
                    ref = PayloadRef(ScreenConstants.PAYLOAD_NAME, ScreenConstants.MEDIA_TYPE, mp4Size, null),
                    captureStartEpochMs = captureStart,
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
                    override fun open(payload: SegmentPayload): InputStream = mp4File.inputStream()
                    override fun release(payload: SegmentPayload) {}
                }

                val sealResult = try {
                    spoolWriter.seal(segment, singleProvider)
                } catch (_: Throwable) {
                    gaps.recordDiagnostic("screen", "seal failed", attemptId, captureStart)
                    continue
                }

                try {
                    sealedSink.persistSealed(segment, sealResult, nowProvider())
                } catch (_: Throwable) {
                    gaps.recordDiagnostic("screen", "seal failed", attemptId, captureStart)
                    continue
                }

                gaps.recordDiagnostic("screen", "delivered", attemptId, captureStart)
                if (!dir.deleteRecursively()) error("screen attempt delete failed")
            } catch (_: Exception) {
                gaps.recordDiagnostic("screen", "seal failed", attemptId, null)
                continue
            }
        }
    }

    private fun keepUndeliverableOrNoBytes(dir: File, attemptId: String, captured: Long?) {
        if (screenPayloadExisted(dir)) keepLoss(dir, attemptId, captured)
        else gaps.recordDiagnostic("screen", "no bytes", attemptId, captured)
    }

    /** A real index sample or a non-empty mp4 is a loss. Configuration-only bytes are not. */
    private fun keepFrameOrNoFrame(dir: File, attemptId: String, captured: Long?) {
        if (screenPayloadExisted(dir)) keepLoss(dir, attemptId, captured)
        else gaps.recordDiagnostic("screen", "no captured frame", attemptId, captured)
    }

    private fun keepLoss(dir: File, attemptId: String, captured: Long?) {
        if (gapLossFactExists(dir.toPath())) return
        if (!gaps.recordLoss("screen", "undeliverable", attemptId, captured)) return
        writeGapLossFact(dir.toPath())
    }

    private fun screenPayloadExisted(dir: File): Boolean {
        val mp4 = File(dir, ScreenConstants.PAYLOAD_NAME)
        if (mp4.isFile && payloadBytesExisted(mp4.length())) return true
        val index = File(dir, "samples.idx")
        if (!index.isFile) return false
        return screenIndexHasRealSample(index.readText())
    }
}
