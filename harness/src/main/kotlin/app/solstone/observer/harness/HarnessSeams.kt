// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.pl.RelayPairLink

interface ObserverLifecycle {
    fun start()
    fun stop()
}

interface DesiredObservingStore {
    fun isDesiredOn(): Boolean
    fun setDesiredOn(on: Boolean)
}

class InMemoryDesiredObservingStore(initial: Boolean = false) : DesiredObservingStore {
    private var desiredOn = initial

    override fun isDesiredOn(): Boolean = desiredOn

    override fun setDesiredOn(on: Boolean) {
        desiredOn = on
    }
}

fun interface HeartbeatFreshness {
    fun isFresh(): Boolean
}

fun interface PairProbe {
    fun pairAndProbe(pairLink: String, deviceLabel: String): HarnessPairProbeResult
}

fun interface RelayPairProbe {
    fun pairOverRelay(link: RelayPairLink, deviceLabel: String): HarnessPairProbeResult
}

fun interface PlStatusProbe {
    fun probe(): HarnessPlStatus
}

interface SyncEnqueue {
    fun enqueuePeriodic()
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
