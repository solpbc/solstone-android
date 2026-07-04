// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.QueueState
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState

enum class PairConnectionMode { PAIRING, ALREADY_CONNECTED, RECONNECTING }

data class HarnessPairProbeResult(
    val handshakePinned: Boolean,
    val pairStatus: Int,
    val statusStatus: Int,
    val statusBody: String,
    val homeLabel: String?,
    val endpointHost: String,
    val endpointPort: Int,
    val connectionMode: PairConnectionMode = PairConnectionMode.PAIRING,
)

sealed interface HarnessPlStatus {
    data object NotPaired : HarnessPlStatus
    data class PairedButUnreachable(val reason: String?) : HarnessPlStatus
    data class Reachable(val status: Int) : HarnessPlStatus
}

data class HarnessEvidenceFile(
    val sourceId: String,
    val name: String,
    val mediaType: String,
    val sha256: String,
    val byteSize: Long,
)

data class HarnessEvidenceSegment(
    val id: String,
    val day: String,
    val stream: String,
    val segment: String,
    val dirSegment: String,
    val state: QueueState,
    val byteSize: Long,
    val sealedAt: Long,
    val files: List<HarnessEvidenceFile>,
)

data class HarnessSyncState(
    val pendingCount: Int,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
)

data class HarnessExportResult(
    val sourcePath: String,
    val destinationPath: String,
    val copiedFileCount: Int,
)

data class HarnessDiagnostics(
    val state: SourceState,
    val reason: ReasonCode,
    val display: String,
)
