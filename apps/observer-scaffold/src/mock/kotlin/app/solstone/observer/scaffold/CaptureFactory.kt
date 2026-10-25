// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import android.content.Context
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.spool.GapNoticeStore
import app.solstone.core.spool.ownerMaterialExists
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.observer.harness.SourceRegistration
import app.solstone.platform.camera.still.CameraLock
import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.power.FileUsableSpaceProvider
import app.solstone.platform.power.StorageStatus
import app.solstone.testing.FakeContinuousSource
import app.solstone.testing.fakePayloadBytes
import java.io.ByteArrayInputStream

fun captureSourceIdentities(): List<CaptureSourceIdentity> = listOf(
    CaptureSourceIdentity(
        sourceId = "audio",
        captureForegroundType = CaptureForegroundType.MICROPHONE,
    ),
    CaptureSourceIdentity(
        sourceId = "location",
        captureForegroundType = CaptureForegroundType.LOCATION,
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
    val audio = FakeContinuousSource(
        sourceId = "audio",
        stream = MAIN_STREAM,
        frameEveryMillis = 300_000,
        frameSizeBytes = 16,
        frameCount = 3,
    )
    val location = FakeContinuousSource(
        sourceId = "location",
        stream = MAIN_STREAM,
        frameEveryMillis = 300_000,
        frameSizeBytes = 24,
        frameCount = 3,
        fixedPayloadName = "location.jsonl",
        mediaType = "application/x-ndjson",
    )
    val engines = mapOf(
        "audio" to (audio to { p: app.solstone.platform.fgs.PermissionStatus -> p.microphoneGranted }),
        "location" to (location to { p: app.solstone.platform.fgs.PermissionStatus -> p.locationGranted }),
    )
    val calendarStorage = StorageStatus(FileUsableSpaceProvider(context.filesDir), 50L * 1024L * 1024L)
    val calendar = if (registerCalendarSource) InertCalendarEngine(calendarStorage::isStorageOk, calendarNeedsAttention) else null
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
            calendar?.let { engine ->
                SourceRegistration(
                    sourceId = "calendar",
                    engine = engine,
                    requiredPermissionsGranted = {
                        context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    },
                    captureForegroundType = null,
                    reasonWhenOff = engine::reasonWhenOff,
                    runsWithoutObserver = true,
                )
            },
        ),
        payloadBytesProvider = object : PayloadBytesProvider {
            override fun open(payload: SegmentPayload) =
                ByteArrayInputStream(fakePayloadBytes(payload.sourceId, payload.ref.name, 0, payload.ref.byteSize.toInt()))
        },
        gaps = context.filesDir.toPath().resolve("gap").let { gapDirectory ->
            GapNoticeStore(
                directory = gapDirectory,
                listener = gapListener,
                materialExists = ownerMaterialExists(gapDirectory),
            )
        },
        recoverAudio = audio@{ _, _, _ ->
            val audio = context.filesDir.resolve("audio-source")
            if (!audio.isFile) return@audio
            context.filesDir.resolve(".audio-recovery-step").writeText("entered")
            throw IllegalStateException("audio recovery")
        },
    )
}
