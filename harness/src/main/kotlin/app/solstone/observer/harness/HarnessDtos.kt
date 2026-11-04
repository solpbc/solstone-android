// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.QueueState
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.core.pl.JournalVersionReading

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

/** A peer that is not the paired journal answered at [address], or through the relay when null. */
data class UnknownJournalAnswer(val address: String?)

sealed interface HarnessPlStatus {
    data object NotPaired : HarnessPlStatus
    /**
     * [address] is the `host:port` the check actually dialed and failed to reach, or null when no
     * dial was attempted (a missing credential or identity, say). [unknownJournal] is set when a
     * peer that is not the paired journal answered during the check. [stopped] is set when the
     * journal's own verdict stopped this credential, and nothing dials it until a new pairing.
     */
    data class PairedButUnreachable(
        val reason: String?,
        val address: String? = null,
        val unknownJournal: UnknownJournalAnswer? = null,
        val stopped: Boolean = false,
    ) : HarnessPlStatus
    data class Reachable(val status: Int) : HarnessPlStatus
}

/**
 * The paired journal itself refuses this device: its verdict stopped the credential, or it answered
 * 401 or 403. Everything else that keeps a device from the journal is not a refusal and must not ask
 * the owner to pair again: a journal answering 500 or 503, alert 46 (it couldn't read its pairing
 * records, so the device retries and keeps the credential), or no answer at all.
 */
fun HarnessPlStatus.journalRefusesThisDevice(): Boolean = when (this) {
    HarnessPlStatus.NotPaired -> false
    is HarnessPlStatus.PairedButUnreachable -> stopped
    is HarnessPlStatus.Reachable -> status == 401 || status == 403
}

fun plStatusText(status: HarnessPlStatus): String =
    when (status) {
        HarnessPlStatus.NotPaired -> "Not paired"
        is HarnessPlStatus.PairedButUnreachable ->
            "Paired but unreachable: ${status.reason?.takeIf(String::isNotBlank) ?: "connection failed (no further detail)"}"
        is HarnessPlStatus.Reachable -> "Reachable (HTTP ${status.status})"
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

data class HarnessBacklogStatus(
    val plStatus: HarnessPlStatus,
    val pendingCount: Int,
    val pendingSourceIds: List<String>,
    val journalVersion: JournalVersionReading? = null,
    val imports: HarnessImportBacklog? = null,
    val calendarHeldForJournal: Boolean = false,
)

/**
 * Owner-shared imports still on this device. [pendingCount] can shrink on its own and counts in
 * the waiting total; [attentionCount] can't (each needs the owner) and is never counted there.
 */
data class HarnessImportBacklog(
    val pendingCount: Int,
    val attentionCount: Int,
    val pairingIncomplete: Boolean,
    val refused: Boolean,
    val gate: app.solstone.platform.work.ImportVersionGate,
    val sending: Boolean,
    val lastDelivery: app.solstone.platform.persistence.room.ImportLastDelivery?,
    val currentInstanceId: String?,
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
