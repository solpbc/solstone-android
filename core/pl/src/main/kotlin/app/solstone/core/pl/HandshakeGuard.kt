// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingPublisher
import app.solstone.core.identity.PairingRefusalStore
import app.solstone.core.model.IdentityState

/**
 * The refusal rule for the three callers that classify a journal's refusal: sync, the status probe
 * and the journal browser. They share one [tracker], so one credential has one count and one stop.
 *
 * A stop is written as the revoked identity state through the pairing publisher's own mutation
 * boundary and generation check, and recorded in [refusalStore]. The revoked state blocks every dial
 * until a new pairing, in this process and every later one.
 */
class HandshakeGuard(
    private val tracker: HandshakeRefusalTracker,
    private val publisher: PairingPublisher,
    private val refusalStore: PairingRefusalStore? = null,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    /**
     * Whether [pairing] must not be dialled: its credential has stopped, or it is no longer paired.
     * A stop that is recorded but not yet written as the revoked state is written now.
     */
    fun stopped(pairing: PairingGeneration): Boolean {
        val stop = tracker.stop(pairing.clientCertFingerprint)
        if (stop != null) {
            enforce(pairing, stop)
            return true
        }
        val snapshot = publisher.currentSnapshot() as? PairingGraphSnapshot.Committed ?: return false
        return snapshot.pairing == pairing && snapshot.home.state == IdentityState.REVOKED
    }

    /** Begin one attempt to reach the journal paired as [pairing]. */
    fun begin(pairing: PairingGeneration): JournalAttempt = JournalAttempt(pairing, this)

    internal fun accepted(pairing: PairingGeneration) {
        if (!current(pairing)) return
        runCatching { tracker.accepted(pairing.clientCertFingerprint) }
            .onFailure { log("handshake refusal tracker failed", it) }
    }

    internal fun failed(pairing: PairingGeneration, failure: HandshakeFailure): HandshakeStop? {
        if (!current(pairing)) return null
        val stop = runCatching { tracker.failed(pairing.clientCertFingerprint, failure) }
            .onFailure { log("handshake refusal tracker failed", it) }
            .getOrNull()
        if (stop != null) enforce(pairing, stop)
        return stop
    }

    /** Only the credential still paired is tracked; an attempt a re-pair overtook says nothing. */
    private fun current(pairing: PairingGeneration): Boolean =
        (publisher.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing == pairing

    private fun enforce(pairing: PairingGeneration, stop: HandshakeStop) {
        val snapshot = publisher.currentSnapshot() as? PairingGraphSnapshot.Committed
        if (snapshot == null || snapshot.pairing != pairing) return
        if (snapshot.home.state != IdentityState.REVOKED) {
            when (val result = publisher.revokePairing(pairing)) {
                is GraphMutationResult.Applied -> log("journal stopped accepting this device: ${stop.name.lowercase()}", null)
                else -> log("could not record the stop: $result", null)
            }
        }
        runCatching { refusalStore?.markRefused(pairing) }
            .onFailure { log("could not record the refusal", it) }
    }
}

/**
 * One try to reach the paired journal, across every leg tried in it: each saved LAN address, then
 * the relay.
 *
 * The first verdict on any leg (the journal's acceptance, 49, 46 or another refusal) decides the
 * attempt, and no further legs are dialled for it. Among legs that ended without a verdict an
 * unknown journal outranks unreachable, and at equal rank the later leg wins. The relay is tried
 * whenever every direct leg ended without a verdict. Every unknown-journal sighting is kept, even
 * when another leg decides.
 *
 * Pass the attempt to the transport as its [JournalVerdictListener], so the verdict is recorded
 * wherever the first read happens; [legFailed] and [answered] record the same verdict at most once.
 */
class JournalAttempt internal constructor(
    val pairing: PairingGeneration,
    private val guard: HandshakeGuard?,
) : JournalVerdictListener {
    // Every verdict is recorded under this lock, the tracker and the stop included, so a reader on
    // another thread never sees the attempt decided before the stop that verdict produced.
    private val lock = Any()
    private var refusalValue: HandshakeFailure? = null
    private var decidedValue = false
    private var stopValue: HandshakeStop? = null

    /** The verdict that decided this attempt, if any: null for acceptance. */
    val refusal: HandshakeFailure?
        get() = synchronized(lock) { refusalValue }

    /** Whether a verdict decided this attempt. */
    val decided: Boolean
        get() = synchronized(lock) { decidedValue }

    /** The stop this attempt's verdict produced, if any; waits for a verdict being recorded. */
    val stop: HandshakeStop?
        get() = synchronized(lock) { stopValue }

    private var noVerdict: HandshakeFailure? = null
    private val sightingsList = ArrayList<UnknownJournalException>()

    /** Every peer seen on this attempt that is not the paired journal. */
    val sightings: List<UnknownJournalException>
        get() = synchronized(lock) { sightingsList.toList() }

    /** How the attempt ended when no leg reached a verdict: unknown journal outranks unreachable. */
    val failureWithoutVerdict: HandshakeFailure?
        get() = synchronized(lock) { noVerdict }

    override fun onAccepted() = answered()

    override fun onRefused(refusal: JournalRefusalException) {
        decide(refusal.failure)
    }

    /** The journal answered on this attempt (any HTTP answer, 401 and 403 included). */
    fun answered() {
        synchronized(lock) {
            if (decidedValue) return
            guard?.accepted(pairing)
            decidedValue = true
        }
    }

    /**
     * One leg failed with [failure]. Returns what the attempt does next: dial the next leg, or stop
     * dialling because a verdict decided it.
     */
    fun legFailed(failure: Throwable): LegOutcome {
        journalRefusalIn(failure)?.let { refusal ->
            decide(refusal.failure)
            return if (stop != null) LegOutcome.STOPPED else LegOutcome.DECIDED
        }
        val unknown = unknownJournalIn(failure)
        synchronized(lock) {
            if (decidedValue) return if (stopValue != null) LegOutcome.STOPPED else LegOutcome.DECIDED
            if (unknown != null) {
                sightingsList += unknown
                noVerdict = HandshakeFailure.UNKNOWN_JOURNAL
            } else if (noVerdict != HandshakeFailure.UNKNOWN_JOURNAL) {
                noVerdict = HandshakeFailure.UNREACHABLE
            }
        }
        return LegOutcome.NEXT_LEG
    }

    /** Close the attempt: one that never reached a verdict is fed to the tracker, which ignores it. */
    fun finish() {
        val outcome = synchronized(lock) { if (decidedValue) null else noVerdict } ?: return
        guard?.failed(pairing, outcome)
    }

    private fun decide(failure: HandshakeFailure) {
        synchronized(lock) {
            if (decidedValue) return
            stopValue = guard?.failed(pairing, failure)
            refusalValue = failure
            decidedValue = true
        }
    }

    companion object {
        /** An attempt that applies the leg rules but records nothing, for callers with no guard. */
        fun untracked(pairing: PairingGeneration): JournalAttempt = JournalAttempt(pairing, null)
    }
}

/** What an attempt does after one leg failed. */
enum class LegOutcome {
    /** No verdict: dial the next leg (the relay included). */
    NEXT_LEG,

    /** A verdict decided the attempt (46, or a refusal below the limit): retry later, dial nothing more now. */
    DECIDED,

    /** A verdict stopped the credential: present the unpaired state. */
    STOPPED,
}
