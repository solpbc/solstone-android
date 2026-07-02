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

fun interface VisibleCaptureAuthority {
    fun isVisibleOwnerPresent(): Boolean
}

object AlwaysVisibleCaptureAuthority : VisibleCaptureAuthority {
    override fun isVisibleOwnerPresent(): Boolean = true
}

class VisibleCaptureOwnerRegistry : VisibleCaptureAuthority {
    private var current: Long? = null
    private var generation: Long = 0

    @Synchronized
    fun acquire(): Long {
        generation += 1
        current = generation
        return generation
    }

    @Synchronized
    fun release(token: Long) {
        if (current == token) current = null
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = current == token

    @Synchronized
    override fun isVisibleOwnerPresent(): Boolean = current != null
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

interface NetworkAvailability {
    fun start(onUsableNetwork: () -> Unit)
    fun stop()
    fun isUsableNow(): Boolean
}

interface EvidenceReader {
    fun listEvidence(): List<HarnessEvidenceSegment>
    fun pendingCount(): Int
    fun syncState(): HarnessSyncState
}

fun interface BundleExport {
    fun export(segment: HarnessEvidenceSegment): HarnessExportResult
}
