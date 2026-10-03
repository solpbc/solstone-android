// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.Context
import android.hardware.SensorManager
import app.solstone.core.metadata.PhotoMetadataContract
import app.solstone.core.metadata.PhotoMetadataEngine
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.UnresolvedInterruptionStore
import app.solstone.platform.audio.AndroidAacAdtsRemuxer
import app.solstone.platform.audio.AudioCommitStatus
import app.solstone.platform.audio.AudioContinuousSourceEngine
import app.solstone.platform.audio.AudioRecoveryManager
import app.solstone.platform.audio.AudioSpoolLookup
import app.solstone.platform.camera.camera2.Camera2StillCamera
import app.solstone.platform.camera.still.CameraLock
import app.solstone.platform.camera.still.StillCaptureEngine
import app.solstone.platform.metadata.AndroidBatterySource
import app.solstone.platform.metadata.AndroidImuSensorPort
import app.solstone.platform.metadata.AndroidMetadataScheduler
import app.solstone.platform.persistence.room.RoomSealedSegmentSink
import app.solstone.platform.persistence.room.occupiedDirSegments
import app.solstone.platform.power.FileUsableSpaceProvider
import app.solstone.platform.power.StorageStatus
import java.nio.file.Files

fun createCaptureSetup(context: Context, cameraLock: CameraLock): CaptureSetup {
    val storageStatus = StorageStatus(FileUsableSpaceProvider(context.filesDir), MIN_FREE_BYTES)
    val interruption = UnresolvedInterruptionStore(context.filesDir.resolve("audio-interruption").toPath())
    val remuxer = AndroidAacAdtsRemuxer()
    val audio = AudioContinuousSourceEngine(
        outputDirectory = context.filesDir.resolve("audio-source"),
        storageStatus = storageStatus,
        remuxer = remuxer,
        interruption = interruption,
    )
    val camera = StillCaptureEngine(
        stillCamera = Camera2StillCamera(context),
        cameraLock = cameraLock,
    )
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val metadataEngine = PhotoMetadataEngine(
        scheduler = AndroidMetadataScheduler(),
        battery = AndroidBatterySource(context),
        imu = AndroidImuSensorPort(sensorManager, System::currentTimeMillis),
    )
    val tappedCamera = CameraTapEngine(camera, metadataEngine::onCameraEmission)
    return CaptureSetup(
        engines = listOf(audio, tappedCamera, metadataEngine),
        payloadBytesProvider = object : PayloadBytesProvider {
            override fun open(payload: SegmentPayload) =
                when (payload.sourceId) {
                    AudioContinuousSourceEngine.SOURCE_ID -> audio.open(payload)
                    StillCaptureEngine.SOURCE_ID -> camera.open(payload)
                    PhotoMetadataContract.SOURCE_ID -> metadataEngine.open(payload)
                    else -> error("unknown payload source: ${payload.sourceId}")
                }

            override fun release(payload: SegmentPayload) {
                when (payload.sourceId) {
                    AudioContinuousSourceEngine.SOURCE_ID -> audio.release(payload)
                    StillCaptureEngine.SOURCE_ID -> camera.release(payload)
                    PhotoMetadataContract.SOURCE_ID -> metadataEngine.release(payload)
                    else -> error("unknown payload source: ${payload.sourceId}")
                }
            }

            override fun discardUncommitted(payload: SegmentPayload): Boolean =
                when (payload.sourceId) {
                    AudioContinuousSourceEngine.SOURCE_ID -> audio.discardUncommitted(payload)
                    StillCaptureEngine.SOURCE_ID -> camera.discardUncommitted(payload)
                    PhotoMetadataContract.SOURCE_ID -> metadataEngine.discardUncommitted(payload)
                    else -> error("unknown payload source: ${payload.sourceId}")
                }
        },
        storageOk = storageStatus::isStorageOk,
        inFlightAudioIds = audio::inFlightRecordingIds,
        recoverAudio = { spoolDir, dao, interruptionStore, inFlightIds ->
            val lookup = object : AudioSpoolLookup {
                override fun findStatus(
                    sha256: String,
                    captureStartEpochMs: Long,
                    captureEndEpochMs: Long,
                    sourceId: String,
                    name: String,
                    day: String,
                    stream: String,
                    segmentLeaf: String,
                ): AudioCommitStatus {
                    val wireId = "$day/$stream/$segmentLeaf"
                    val rows = (dao.duplicateBySha256(sha256) + dao.filesBySegmentId(wireId)).distinctBy { it.rowId }
                    val facts = rows.mapNotNull { fileRow ->
                        val segmentRow = dao.segmentById(fileRow.segmentId) ?: return@mapNotNull null
                        val spoolPayload = spoolDir.resolve(segmentRow.day).resolve(segmentRow.stream).resolve(segmentRow.dirSegment).resolve(fileRow.name)
                        val exists = Files.isRegularFile(spoolPayload)
                        val matches = exists && runCatching { app.solstone.core.segment.sha256(spoolPayload) == fileRow.sha256 }.getOrDefault(false)
                        app.solstone.platform.audio.AudioSpoolFact(
                            segmentId = fileRow.segmentId,
                            sha256 = fileRow.sha256,
                            captureStartEpochMs = fileRow.captureStartEpochMs,
                            captureEndEpochMs = fileRow.captureEndEpochMs,
                            sourceId = fileRow.sourceId,
                            name = fileRow.name,
                            spoolPayloadExists = exists,
                            spoolPayloadShaMatches = matches,
                            spoolCustodyDurable = matches && app.solstone.core.spool.confirmDurableSpoolCustody(spoolDir, spoolPayload.parent),
                            confirmedUploaded = segmentRow.state == app.solstone.core.model.QueueState.UPLOADED ||
                                segmentRow.state == app.solstone.core.model.QueueState.EVICTED,
                        )
                    }
                    return app.solstone.platform.audio.classifyAudioAttempt(
                        sha256 = sha256,
                        captureStartEpochMs = captureStartEpochMs,
                        captureEndEpochMs = captureEndEpochMs,
                        sourceId = sourceId,
                        name = name,
                        day = day,
                        stream = stream,
                        segmentLeaf = segmentLeaf,
                        facts = facts,
                    )
                }

                override fun legacyManifestCoordinatesMatch(sha256: String): Boolean {
                    val rows = dao.duplicateBySha256(sha256)
                    val facts = rows.mapNotNull { fileRow ->
                        val segmentRow = dao.segmentById(fileRow.segmentId) ?: return@mapNotNull null
                        val segDir = spoolDir.resolve(segmentRow.day).resolve(segmentRow.stream).resolve(segmentRow.dirSegment)
                        val manifestPath = segDir.resolve("manifest")
                        if (!Files.isRegularFile(manifestPath)) return@mapNotNull null
                        val parsed = runCatching { app.solstone.core.spool.parseManifest(String(Files.readAllBytes(manifestPath), java.nio.charset.StandardCharsets.UTF_8)) }.getOrNull() ?: return@mapNotNull null
                        if (parsed.zoneId.isBlank()) return@mapNotNull null
                        val manifestFile = parsed.manifest.files.firstOrNull { it.sha256 == sha256 } ?: return@mapNotNull null
                        val spoolPayload = segDir.resolve(manifestFile.name)
                        if (!Files.isRegularFile(spoolPayload)) return@mapNotNull null
                        val matches = runCatching { app.solstone.core.segment.sha256(spoolPayload) == sha256 }.getOrDefault(false)
                        app.solstone.platform.audio.AudioSpoolFact(
                            segmentId = fileRow.segmentId,
                            sha256 = sha256,
                            captureStartEpochMs = manifestFile.captureStartEpochMs,
                            captureEndEpochMs = manifestFile.captureEndEpochMs,
                            sourceId = manifestFile.sourceId,
                            name = manifestFile.name,
                            spoolPayloadExists = true,
                            spoolPayloadShaMatches = matches,
                            zoneId = parsed.zoneId,
                            spoolCustodyDurable = matches && app.solstone.core.spool.confirmDurableSpoolCustody(spoolDir, segDir),
                        )
                    }
                    return app.solstone.platform.audio.legacyCacheAlreadyDelivered(sha256, facts)
                }
            }
            val manager = AudioRecoveryManager(
                audioSourceDir = context.filesDir.resolve("audio-source"),
                cacheAudioDir = context.cacheDir.resolve("audio-source"),
                spoolDir = spoolDir,
                occupiedLeaves = dao::occupiedDirSegments,
                lookup = lookup,
                sealedSink = RoomSealedSegmentSink(dao),
                interruption = interruptionStore,
                remuxer = remuxer,
            )
            manager.recover(inFlightIds)
        },
    )
}

private const val MIN_FREE_BYTES = 50L * 1024L * 1024L
