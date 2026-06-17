// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import app.solstone.core.segment.SealedSegment

fun interface SealedSegmentSink {
    fun persistSealed(segment: SealedSegment, result: SealResult, sealedAtEpochMs: Long)
}
