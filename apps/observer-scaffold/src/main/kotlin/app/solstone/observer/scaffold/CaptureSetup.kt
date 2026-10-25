// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import app.solstone.core.spool.GapNoticeStore
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.observer.harness.SourceRegistration
import app.solstone.platform.persistence.room.SegmentDao
import java.nio.file.Path

data class CaptureSourceIdentity(
    val sourceId: String,
    val captureForegroundType: app.solstone.platform.fgs.CaptureForegroundType?,
)

data class CaptureSetup(
    val registrations: List<SourceRegistration>,
    val payloadBytesProvider: PayloadBytesProvider,
    val storageOk: () -> Boolean = { true },
    val inFlightAudioIds: () -> Set<String> = { emptySet() },
    val gaps: GapNoticeStore? = null,
    val recoverAudio: (Path, SegmentDao, Set<String>) -> Unit = { _, _, _ -> },
    val inFlightScreenIds: () -> Set<String> = { emptySet() },
    val recoverScreen: (Path, SegmentDao, Set<String>) -> Unit = { _, _, _ -> },
)

/** A screen session that did not start is one diagnostic. It does not set the engine's failure reason. */
fun recordScreenDidNotStart(
    started: Boolean,
    record: (source: String, reason: String, material: String) -> Unit,
) {
    if (!started) record("screen", "screen did not start", "screen-start")
}
