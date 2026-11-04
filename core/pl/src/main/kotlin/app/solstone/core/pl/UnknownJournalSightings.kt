// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.JournalMark
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** What can be drawn for the peer that answered. Its journal ID is only ever a claim. */
sealed interface ClaimedJournalMark {
    /** The peer presented no journal ID. */
    data object NotPresented : ClaimedJournalMark

    /** The mark of the claimed ID is being worked out. */
    data object Pending : ClaimedJournalMark

    data class Derived(val mark: JournalMark) : ClaimedJournalMark

    /** The peer claimed an ID, and this phone couldn't work out its mark. */
    data object Unavailable : ClaimedJournalMark
}

/**
 * A peer that answered where the paired journal was expected and is not it. [address] is the direct
 * `host:port` it answered at, or null through the relay.
 */
data class UnknownJournalSighting(
    val address: String?,
    val claimedJournalId: String?,
    val mark: ClaimedJournalMark,
)

/**
 * One sighting per address, kept in memory for this process, so a restart clears them.
 *
 * A sighting ends when a later dial to its address reaches the paired journal. There is no dismissal:
 * this list is the whole state of the notice. A mark is worked out once per sighting, off the caller's
 * thread, and again only when the address's claimed journal ID changes. Listeners hear about a change
 * to the list, never about a repeat of a sighting it already holds, so a listener may safely read
 * status without looping.
 *
 * Sightings are measured against one pairing. [epoch] moves when that pairing leaves, and a dial that
 * started before then reports nothing.
 */
class UnknownJournalSightings(
    private val derive: (String) -> JournalMark = ::journalMarkFromJid,
    private val executor: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "unknown-journal-mark").apply { isDaemon = true }
    },
) {
    private val lock = Any()
    private val rows = LinkedHashMap<String?, UnknownJournalSighting>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var followed: PairingGeneration? = null
    private var following = false

    @Volatile
    var epoch: Long = 0L
        private set

    fun seen(address: String?, claimedJournalId: String?, startedAt: Long = epoch) {
        val claim = claimedJournalId?.takeIf(String::isNotBlank)
        synchronized(lock) {
            if (startedAt != epoch) return
            val existing = rows[address]
            if (existing != null && existing.claimedJournalId == claim) return
            rows[address] = UnknownJournalSighting(
                address = address,
                claimedJournalId = claim,
                mark = if (claim == null) ClaimedJournalMark.NotPresented else ClaimedJournalMark.Pending,
            )
        }
        notifyListeners()
        if (claim != null) executor.execute { deriveFor(address, claim) }
    }

    /** A dial to [address], or through the relay when null, reached the paired journal. */
    fun reached(address: String?) {
        val removed = synchronized(lock) { rows.remove(address) != null }
        if (removed) notifyListeners()
    }

    /** The pairing these sightings were measured against is gone. */
    fun clear() {
        val removed = synchronized(lock) {
            epoch += 1
            val any = rows.isNotEmpty()
            rows.clear()
            any
        }
        if (removed) notifyListeners()
    }

    /**
     * Follows the pairing authority: a different pairing ends every sighting, and an address that is
     * no longer saved loses its row, because nothing will dial it to clear it. Returns whether the
     * pairing changed. An unreadable pairing says nothing either way.
     */
    fun followPairing(snapshot: PairingGraphSnapshot): Boolean {
        if (snapshot is PairingGraphSnapshot.Uncertain) return false
        val committed = snapshot as? PairingGraphSnapshot.Committed
        val pairing = committed?.pairing
        val changed = synchronized(lock) {
            val differs = following && followed != pairing
            following = true
            followed = pairing
            differs
        }
        if (changed) {
            clear()
        } else if (committed != null) {
            val saved = committed.directEndpoints.map { displayAddress(it.host, it.port) }.toSet()
            val removed = synchronized(lock) { rows.keys.removeAll { it != null && it !in saved } }
            if (removed) notifyListeners()
        }
        return changed
    }

    fun current(): List<UnknownJournalSighting> = synchronized(lock) { rows.values.toList() }

    /** Called off the main thread. Returns what removes the listener. */
    fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun deriveFor(address: String?, claim: String) {
        val mark: ClaimedJournalMark = runCatching { derive(claim) }.fold(
            onSuccess = { ClaimedJournalMark.Derived(it) },
            onFailure = { ClaimedJournalMark.Unavailable },
        )
        val changed = synchronized(lock) {
            val row = rows[address]
            if (row == null || row.claimedJournalId != claim || row.mark != ClaimedJournalMark.Pending) {
                false
            } else {
                rows[address] = row.copy(mark = mark)
                true
            }
        }
        if (changed) notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { listener -> runCatching { listener() } }
    }
}
