// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import android.content.Context
import android.os.Build
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.spool.GapNoticeStore
import app.solstone.core.spool.PayloadBytesProvider
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
import app.solstone.platform.screen.AndroidScreenProjectionManager
import app.solstone.platform.screen.AndroidScreenRemuxer
import app.solstone.platform.screen.ScreenConstants
import app.solstone.platform.screen.ScreenContinuousSourceEngine
import app.solstone.platform.screen.ScreenRecoveryManager
import app.solstone.platform.screen.ScreenSessionHolder
import app.solstone.platform.fgs.ObserverForegroundService
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

fun createCaptureSetup(
    context: Context,
    cameraLock: CameraLock,
    registerScreenSource: Boolean = false,
    registerCalendarSource: Boolean = false,
    calendarNeedsAttention: () -> Boolean = { false },
    gapListener: (String, String, String, String) -> Unit = { _, _, _, _ -> },
): CaptureSetup {
    val storageStatus = StorageStatus(FileUsableSpaceProvider(context.filesDir), MIN_FREE_BYTES)
    val gaps = GapNoticeStore(
        directory = context.filesDir.resolve("gap").toPath(),
        listener = gapListener,
    )
    val remuxer = AndroidAacAdtsRemuxer()
    val audio = AudioContinuousSourceEngine(
        outputDirectory = context.filesDir.resolve("audio-source"),
        storageStatus = storageStatus,
        remuxer = remuxer,
        gaps = gaps,
    )
    val location = LocationContinuousSourceEngine(AndroidLocationSource(context))
    val camera = StillCaptureEngine(
        stillCamera = selectStillCamera(context),
        cameraLock = cameraLock,
    )
    val screenStorageStatus = if (registerScreenSource) {
        StorageStatus(FileUsableSpaceProvider(context.filesDir), SCREEN_MIN_FREE_BYTES)
    } else null

    val screenSessionHolder = if (registerScreenSource) {
        val projManager = AndroidScreenProjectionManager(context)
        ScreenSessionHolder(
            projectionManager = projManager,
            getDisplayMetrics = { context.resources.displayMetrics },
            onSessionEnded = {
                if (!ObserverForegroundService.isScreenSessionLive()) {
                    ObserverForegroundService.sessionPromotable.set(false)
                    ObserverForegroundService.onScreenSessionEnded?.invoke()
                }
            },
        ).also { holder ->
            ObserverForegroundService.screenSessionStarter = ObserverForegroundService.Companion.ScreenSessionStarter { _, token ->
                holder.startSession(context, token).also { started ->
                    recordScreenDidNotStart(started) { source, reason, material ->
                        gaps.recordDiagnostic(source, reason, material, null)
                    }
                }
            }
            ObserverForegroundService.screenSessionStopper = {
                holder.stopSession()
                ObserverForegroundService.sessionPromotable.set(false)
                ObserverForegroundService.onScreenSessionEnded?.invoke()
            }
            ObserverForegroundService.isScreenSessionLive = {
                holder.isSessionLive()
            }
            ObserverForegroundService.screenStorageOkCheck = {
                screenStorageStatus?.isStorageOk() ?: true
            }
        }
    } else null

    val screenDiag: (String) -> Unit = { android.util.Log.i("ScreenSource", it) }
    val screenRemuxer = if (registerScreenSource) AndroidScreenRemuxer(screenDiag) else null
    val screen = if (registerScreenSource && screenSessionHolder != null && screenRemuxer != null && screenStorageStatus != null) {
        ScreenContinuousSourceEngine(
            outputDirectory = context.filesDir.resolve("screen-source"),
            storageStatus = screenStorageStatus,
            remuxer = screenRemuxer,
            gaps = gaps,
            sessionHolder = screenSessionHolder,
            diag = screenDiag,
            onFloorTripped = { android.os.Handler(android.os.Looper.getMainLooper()).post { if (!screenSessionHolder.isSessionLive()) ObserverForegroundService.screenSessionStopper?.invoke() } },
            onFailed = { android.os.Handler(android.os.Looper.getMainLooper()).post { if (!screenSessionHolder.isSessionLive()) ObserverForegroundService.screenSessionStopper?.invoke() } },
        )
    } else null

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
        } + listOfNotNull(
            if (registerCalendarSource) {
                val calendar = InertCalendarEngine(storageStatus::isStorageOk, calendarNeedsAttention)
                SourceRegistration(
                    sourceId = "calendar",
                    engine = calendar,
                    requiredPermissionsGranted = {
                        context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    },
                    captureForegroundType = null,
                    reasonWhenOff = calendar::reasonWhenOff,
                    runsWithoutObserver = true,
                )
            } else {
                null
            },
            screen?.let { s ->
                SourceRegistration(
                    sourceId = ScreenConstants.SOURCE_ID,
                    engine = s,
                    // requiredPermissionsGranted returns true because mediaProjection has no Android runtime
                    // permission to grant/revoke in PermissionStatus; capturePermission is null and
                    // capturePermissionGranted is false so the migration backfill in SourceRegistry does not
                    // see an affirmative runtime grant and manufacture an On wish.
                    requiredPermissionsGranted = { true },
                    captureForegroundType = CaptureForegroundType.MEDIA_PROJECTION,
                    reasonWhenOff = s::reasonWhenOff,
                )
            }
        ),
        payloadBytesProvider = object : PayloadBytesProvider {
            override fun open(payload: SegmentPayload) =
                when (payload.sourceId) {
                    AudioContinuousSourceEngine.SOURCE_ID -> audio.open(payload)
                    LocationContinuousSourceEngine.SOURCE_ID -> location.open(payload)
                    StillCaptureEngine.SOURCE_ID -> camera.open(payload)
                    ScreenConstants.SOURCE_ID -> screen?.open(payload) ?: error("unknown payload source: ${payload.sourceId}")
                    else -> error("unknown payload source: ${payload.sourceId}")
                }

            override fun release(payload: SegmentPayload) {
                when (payload.sourceId) {
                    AudioContinuousSourceEngine.SOURCE_ID -> audio.release(payload)
                    LocationContinuousSourceEngine.SOURCE_ID -> location.release(payload)
                    StillCaptureEngine.SOURCE_ID -> camera.release(payload)
                    ScreenConstants.SOURCE_ID -> screen?.release(payload)
                    else -> error("unknown payload source: ${payload.sourceId}")
                }
            }

            override fun discardUncommitted(payload: SegmentPayload): Boolean =
                when (payload.sourceId) {
                    AudioContinuousSourceEngine.SOURCE_ID -> audio.discardUncommitted(payload)
                    LocationContinuousSourceEngine.SOURCE_ID -> {
                        val dropped = location.discardUncommitted(payload)
                        recordLateFragment(dropped, "location") { source, reason, material ->
                            gaps.recordDiagnostic(source, reason, material, null)
                        }
                        dropped
                    }
                    StillCaptureEngine.SOURCE_ID -> {
                        val dropped = camera.discardUncommitted(payload)
                        recordLateFragment(dropped, "camera") { source, reason, material ->
                            gaps.recordDiagnostic(source, reason, material, null)
                        }
                        dropped
                    }
                    ScreenConstants.SOURCE_ID -> screen?.discardUncommitted(payload) ?: true
                    else -> error("unknown payload source: ${payload.sourceId}")
                }
        },
        storageOk = storageStatus::isStorageOk,
        inFlightAudioIds = audio::inFlightRecordingIds,
        gaps = gaps,
        recoverAudio = { spoolDir, dao, inFlightIds ->
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
                gaps = gaps,
                remuxer = remuxer,
            )
            manager.recover(inFlightIds)
        },
        inFlightScreenIds = { screen?.inFlightRecordingIds() ?: emptySet() },
        recoverScreen = { spoolDir, dao, inFlightIds ->
            if (screenRemuxer != null) {
                val factsFunction = { day: String, stream: String, segmentLeaf: String, sha256: String ->
                    val wireId = "$day/$stream/$segmentLeaf"
                    val rows = (dao.duplicateBySha256(sha256) + dao.filesBySegmentId(wireId)).distinctBy { it.rowId }
                    rows.mapNotNull { fileRow ->
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
                }
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
                        val facts = factsFunction(day, stream, segmentLeaf, sha256)
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
                }
                val manager = ScreenRecoveryManager(
                    screenSourceDir = context.filesDir.resolve("screen-source"),
                    spoolDir = spoolDir,
                    occupiedLeaves = dao::occupiedDirSegments,
                    lookup = lookup,
                    sealedSink = RoomSealedSegmentSink(dao),
                    gaps = gaps,
                    remuxer = screenRemuxer,
                    factsProvider = factsFunction,
                )
                manager.recover(inFlightIds)
            }
        },
    )
}

private fun selectStillCamera(context: Context): StillCamera =
    if (Build.VERSION.SDK_INT >= 29) Camera2StillCamera(context) else LegacyStillCamera(context)

private const val MIN_FREE_BYTES = 50L * 1024L * 1024L

// Each elementary-stream portion has a hard 32 MiB ceiling, independent of codec bitrate.
// Published MP4s are capped at 33 MiB. The floor retains room for remux and spool copies
// beside the original, the other sources' unchanged 50 MiB floor, and filesystem overhead.
// The same portion limit bounds the existing in-memory multipart upload.
private const val SCREEN_MIN_FREE_BYTES = 256L * 1024L * 1024L
