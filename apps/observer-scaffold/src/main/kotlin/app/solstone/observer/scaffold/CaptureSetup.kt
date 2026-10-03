// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.UnresolvedInterruption
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
    val recoverAudio: (Path, SegmentDao, UnresolvedInterruption, Set<String>) -> Unit = { _, _, _, _ -> },
)
