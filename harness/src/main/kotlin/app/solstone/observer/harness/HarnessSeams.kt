// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.pl.RelayPairLink

interface ObserverLifecycle {
    fun start()
    fun startWhenAlreadyForeground() = start()
    fun restartCaptureForHeldTypes() { stop(); start() }
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

/**
 * What the foreground-service heartbeat instrument knows.
 *
 * ⚠ [isFresh] returning false has **two** causes and only one of them is a fault: a service that
 * beat and then went silent, and a service that has never been asked to start at all. Diagnosing
 * the second as `SERVICE_KILLED` tells the owner intake "was stopped by the system" when nothing
 * ever started it. [hasStartEvidence] separates them; it defaults true so a fixture that only
 * models freshness keeps its existing meaning.
 */
fun interface HeartbeatFreshness {
    fun isFresh(): Boolean

    fun hasStartEvidence(): Boolean = true
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

interface BacklogStatusReader {
    fun read(): HarnessBacklogStatus
}

fun interface BundleExport {
    fun export(segment: HarnessEvidenceSegment): HarnessExportResult
}
