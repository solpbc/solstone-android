// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.spool.GapNoticeStore
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.platform.persistence.room.SegmentDao
import java.nio.file.Path

data class CaptureSetup(
    val engines: List<ContinuousSourceEngine>,
    val payloadBytesProvider: PayloadBytesProvider,
    val storageOk: () -> Boolean = { true },
    val inFlightAudioIds: () -> Set<String> = { emptySet() },
    val gaps: GapNoticeStore? = null,
    val recoverAudio: (Path, SegmentDao, Set<String>) -> Unit = { _, _, _ -> },
)
