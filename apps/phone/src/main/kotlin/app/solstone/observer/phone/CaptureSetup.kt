// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.segment.MonotonicClock
import app.solstone.core.segment.SegmenterAnchor
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.spool.PayloadBytesProvider

data class CaptureSetup(
    val engine: ContinuousSourceEngine,
    val clock: MonotonicClock,
    val anchor: SegmenterAnchor,
    val payloadBytesProvider: PayloadBytesProvider,
)
