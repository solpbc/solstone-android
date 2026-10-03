// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import android.content.Context
import android.os.Build
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.UnresolvedInterruptionStore
import app.solstone.observer.harness.SourceRegistration
import app.solstone.platform.audio.AndroidAacAdtsRemuxer
import app.solstone.platform.audio.AudioCommitStatus
import app.solstone.platform.audio.AudioContinuousSourceEngine
import app.solstone.platform.audio.AudioRecoveryManager
import app.solstone.platform.audio.AudioSpoolLookup
import app.solstone.platform.camera.camera2.Camera2StillCamera
import app.solstone.platform.camera.legacy.LegacyStillCamera
import app.solstone.platform.camera.still.CameraLock
import app.solstone.platform.camera.still.StillCamera
import app.solstone.platform.camera.still.StillCaptureEngine
import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.location.AndroidLocationSource
import app.solstone.platform.location.LocationContinuousSourceEngine
import app.solstone.platform.persistence.room.RoomSealedSegmentSink
import app.solstone.platform.persistence.room.occupiedDirSegments
import app.solstone.platform.power.FileUsableSpaceProvider
import app.solstone.platform.power.StorageStatus
import java.nio.file.Files

fun captureSourceIdentities(): List<CaptureSourceIdentity> = listOf(
    CaptureSourceIdentity(
        sourceId = AudioContinuousSourceEngine.SOURCE_ID,
        captureForegroundType = CaptureForegroundType.MICROPHONE,
    ),
    CaptureSourceIdentity(
        sourceId = LocationContinuousSourceEngine.SOURCE_ID,
        captureForegroundType = CaptureForegroundType.LOCATION,
    ),
    CaptureSourceIdentity(
        sourceId = StillCaptureEngine.SOURCE_ID,
        captureForegroundType = CaptureForegroundType.CAMERA,
    ),
)

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
    val location = LocationContinuousSourceEngine(AndroidLocationSource(context))
    val camera = StillCaptureEngine(
        stillCamera = selectStillCamera(context),
        cameraLock = cameraLock,
    )
    val engines = mapOf(
        AudioContinuousSourceEngine.SOURCE_ID to (audio to { p: app.solstone.platform.fgs.PermissionStatus -> p.microphoneGranted }),
        LocationContinuousSourceEngine.SOURCE_ID to (location to { p: app.solstone.platform.fgs.PermissionStatus -> p.locationGranted }),
        StillCaptureEngine.SOURCE_ID to (camera to { p: app.solstone.platform.fgs.PermissionStatus -> p.cameraGranted }),
    )
    return CaptureSetup(
        registrations = captureSourceIdentities().map { identity ->
            val (engine, permCheck) = engines.getValue(identity.sourceId)
            SourceRegistration(
                sourceId = identity.sourceId,
                engine = engine,
                requiredPermissionsGranted = permCheck,
                captureForegroundType = identity.captureForegroundType,
            )
        },
        payloadBytesProvider = object : PayloadBytesProvider {
            override fun open(payload: SegmentPayload) =
                when (payload.sourceId) {
                    AudioContinuousSourceEngine.SOURCE_ID -> audio.open(payload)
                    LocationContinuousSourceEngine.SOURCE_ID -> location.open(payload)
                    StillCaptureEngine.SOURCE_ID -> camera.open(payload)
                    else -> error("unknown payload source: ${payload.sourceId}")
                }

            override fun release(payload: SegmentPayload) {
                when (payload.sourceId) {
                    AudioContinuousSourceEngine.SOURCE_ID -> audio.release(payload)
                    LocationContinuousSourceEngine.SOURCE_ID -> location.release(payload)
                    StillCaptureEngine.SOURCE_ID -> camera.release(payload)
                    else -> error("unknown payload source: ${payload.sourceId}")
                }
            }

            override fun discardUncommitted(payload: SegmentPayload): Boolean =
                when (payload.sourceId) {
                    AudioContinuousSourceEngine.SOURCE_ID -> audio.discardUncommitted(payload)
                    LocationContinuousSourceEngine.SOURCE_ID -> location.discardUncommitted(payload)
                    StillCaptureEngine.SOURCE_ID -> camera.discardUncommitted(payload)
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

private fun selectStillCamera(context: Context): StillCamera =
    if (Build.VERSION.SDK_INT >= 29) Camera2StillCamera(context) else LegacyStillCamera(context)

private const val MIN_FREE_BYTES = 50L * 1024L * 1024L
