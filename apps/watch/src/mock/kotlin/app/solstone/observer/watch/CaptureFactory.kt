// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.watch

import android.content.Context
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.segment.SegmenterAnchor
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.testing.BASE_CAPTURE_EPOCH_MS
import app.solstone.testing.FakeContinuousSource
import app.solstone.testing.VirtualMonotonicClock
import app.solstone.testing.fakePayloadBytes
import java.io.ByteArrayInputStream
import java.time.ZoneId

fun createCaptureSetup(context: Context): CaptureSetup {
    val clock = VirtualMonotonicClock(0)
    val engine = FakeContinuousSource(
        sourceId = "audio",
        clock = clock,
        frameEveryMillis = 300_000,
        frameSizeBytes = 16,
        frameCount = 3,
    )
    return CaptureSetup(
        engine = engine,
        clock = clock,
        anchor = SegmenterAnchor(BASE_CAPTURE_EPOCH_MS, 0, ZoneId.systemDefault()),
        payloadBytesProvider = PayloadBytesProvider { payload: SegmentPayload ->
            ByteArrayInputStream(fakePayloadBytes(payload.sourceId, payload.ref.name, 0, payload.ref.byteSize.toInt()))
        },
    )
}
