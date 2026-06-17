// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

interface ObserverLifecycle {
    fun start()
    fun stop()
}

fun interface HeartbeatFreshness {
    fun isFresh(): Boolean
}

fun interface PairProbe {
    fun pairAndProbe(pairLink: String, deviceLabel: String): HarnessPairProbeResult
}

fun interface PlStatusProbe {
    fun probe(): HarnessPlStatus
}

fun interface SyncEnqueue {
    fun enqueueNow()
}

interface EvidenceReader {
    fun listEvidence(): List<HarnessEvidenceSegment>
    fun pendingCount(): Int
    fun syncState(): HarnessSyncState
}

fun interface BundleExport {
    fun export(segment: HarnessEvidenceSegment): HarnessExportResult
}
