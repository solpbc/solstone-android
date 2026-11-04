// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.JournalMark
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

    fun seen(address: String?, claimedJournalId: String?) {
        val claim = claimedJournalId?.takeIf(String::isNotBlank)
        synchronized(lock) {
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
            val any = rows.isNotEmpty()
            rows.clear()
            any
        }
        if (removed) notifyListeners()
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
