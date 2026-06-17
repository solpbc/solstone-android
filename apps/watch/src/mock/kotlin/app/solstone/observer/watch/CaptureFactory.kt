// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.watch

import android.content.Context
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.LOCATION_STREAM
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.platform.camera.still.CameraLock
import app.solstone.testing.FakeContinuousSource
import app.solstone.testing.fakePayloadBytes
import java.io.ByteArrayInputStream

fun createCaptureSetup(context: Context, cameraLock: CameraLock): CaptureSetup {
    val audio = FakeContinuousSource(
        sourceId = "audio",
        stream = MAIN_STREAM,
        frameEveryMillis = 300_000,
        frameSizeBytes = 16,
        frameCount = 3,
    )
    val location = FakeContinuousSource(
        sourceId = "location",
        stream = LOCATION_STREAM,
        frameEveryMillis = 300_000,
        frameSizeBytes = 24,
        frameCount = 3,
        fixedPayloadName = "location.jsonl",
        mediaType = "application/x-ndjson",
    )
    return CaptureSetup(
        engines = listOf(audio, location),
        payloadBytesProvider = PayloadBytesProvider { payload: SegmentPayload ->
            ByteArrayInputStream(fakePayloadBytes(payload.sourceId, payload.ref.name, 0, payload.ref.byteSize.toInt()))
        },
    )
}
