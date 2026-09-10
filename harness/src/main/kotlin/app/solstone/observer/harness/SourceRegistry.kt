// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.diagnostics.PairingFact
import app.solstone.core.diagnostics.SourceFacts
import app.solstone.core.diagnostics.reduce
import app.solstone.core.model.SilencedFact
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.SourceCondition
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.fgs.capturePermission
import app.solstone.platform.fgs.capturePermissionGranted
import app.solstone.platform.fgs.PermissionStatus

class SourcesSubscription(private val closeAction: () -> Unit) {
    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        closeAction()
    }
}

interface SourcesReader {
    fun snapshot(): SourcesReadModel
    fun setWish(sourceId: String, wish: SourceWish): SourceToggleResult
    fun subscribe(listener: SourcesChangeListener): SourcesSubscription

    /**
     * The runtime permissions this one source needs, so it can be asked for its own and nothing
     * else.
     *
     * Empty for an unknown source and for a source that declares no capture type — ⛔ an empty
     * result means *ask for nothing*, never *ask for everything*, which is the behaviour this
     * replaces. ✅ That is also why the default is empty rather than abstract: asking for nothing is
     * the fail-safe direction, so a fake that has no opinion cannot accidentally over-request.
     */
    fun requiredPermissions(sourceId: String): List<String> = emptyList()
}

class SourceRegistry(
    private val controller: HarnessController,
    registrations: List<SourceRegistration>,
    private val main: MainPoster,
    private val wishStore: SourceWishStore,
) : SourcesReader {
    private val lock = Any()
    private val wishes = LinkedHashMap<String, SourceWish>()

    /**
     * The entries the store actually holds — ⛔ never the default-filled [wishes] map.
     *
     * 🔴 `setWish` used to persist `wishes.toMap()`, and `wishes` is built by filling every
     * registration with a default. So **one owner act on one source wrote an explicit entry for
     * every registered source.** Invisible while the written value equalled the default; the moment
     * absence carries meaning it manufactures owner intent for sources nobody chose.
     */
    private val persisted = LinkedHashMap<String, SourceWish>()

    /** Ids the owner has actually expressed a wish for. ⛔ Not the same set as [wishes]'s keys. */
    private val expressed = LinkedHashSet<String>()

    /** A store that exists and would not read. ⛔ Not the same as one that is absent. */
    private var storeUnreadable = false
    private val bound: List<BoundSource>
    private val listeners = mutableListOf<SourcesChangeListener>()

    val engines: List<ContinuousSourceEngine>

    init {
        require(registrations.all { it.sourceId.isNotBlank() }) { "sourceId must be non-blank" }
        val ids = registrations.map { it.sourceId }
        require(ids.size == ids.toSet().size) { "sourceId values must be unique" }
        when (val state = wishStore.read()) {
            is WishStoreState.Loaded -> {
                persisted.putAll(state.wishes)
                expressed.addAll(state.wishes.keys)
            }
            WishStoreState.Absent -> Unit
            WishStoreState.Unreadable -> {
                // ⛔ Do NOT fall through to "nothing expressed". A store that will not read tells us
                // nothing about what the owner chose, so we keep today's behaviour — every source
                // resolves on and actuates — rather than reporting the whole install as never-set-up.
                storeUnreadable = true
                registrations.forEach { expressed.add(it.sourceId) }
            }
        }
        // 🔴 `?: SourceWish.Off`, and the flip is the behaviour half of this rule. A source the owner
        // has never asked for is NOT ACTUATED — "reads ready to set up" and "is not running" are one
        // state, never a label over a source that is quietly on. [expressed] is what tells this
        // resolved `Off` apart from an `Off` the owner chose.
        registrations.forEach { wishes[it.sourceId] = persisted[it.sourceId] ?: SourceWish.Off }
        bound = registrations.map(::BoundSource)
        engines = bound
        controller.sourcesReader = this
        backfillAlreadyRunningSources()
    }

    override fun snapshot(): SourcesReadModel {
        val inputs = controller.globalFactInputs()
        val globalFacts = sourceFactsFor(inputs)
        // ⚠ The observer row is an aggregate, not a source, so it has no wish of its own to express.
        // Leaving the fact at its `true` default keeps it out of the new branch deliberately.
        val (state, reason) = reduce(globalFacts)
        return SourcesReadModel(
            observer = ObserverStatus(
                state = state,
                reason = reason,
                paired = globalFacts.pairing == PairingFact.PAIRED,
            ),
            sources = bound.map { it.status(globalFacts, inputs.permissionStatus) },
        )
    }

    override fun setWish(sourceId: String, wish: SourceWish): SourceToggleResult {
        val wrapper = synchronized(lock) {
            if (sourceId !in wishes) return SourceToggleResult.UnknownSource
            wishes[sourceId] = wish
            // ⛔ One source's act writes one source's entry. Persisting the default-filled map here
            // is what manufactured expressed wishes for every other source.
            persistWish(sourceId, wish)
            bound.first { it.sourceId == sourceId }
        }
        val result = wrapper.actuate()
        notifyListeners()
        return result
    }

    /** ⚠ Call under [lock]. */
    private fun persistWish(sourceId: String, wish: SourceWish) {
        persisted[sourceId] = wish
        expressed.add(sourceId)
        wishStore.saveAll(persisted.toMap())
    }

    /**
     * An already-running source has an expressed wish, whatever the store says.
     *
     * 🔴 **A wish store that predates this rule is not evidence of absence — it is a store that was
     * never asked the question.** No permission path ever wrote a wish, so every owner who granted a
     * permission on an earlier build has an empty store while their sources have been capturing for
     * months. Without this they read correctly until the day they revoke a permission in system
     * settings, and then a source that ran for months reports never-set-up — swallowing a genuine
     * fault across the installed base while looking like the fix working.
     *
     * ✅ On this platform a granted permission **is** the proof it ran: the wish defaulted on, so a
     * granted permission meant the engine was actuated on every build up to now.
     *
     * ⚠ Runs at construction, once per process, off any render or refresh path — ⛔ never on the
     * status-poll cadence. It needs no persisted marker: after the first successful pass the entries
     * exist, so it is idempotent, and if the permission read is not yet real it simply writes
     * nothing and a later launch retries. ⛔ It never writes over an entry the owner already has.
     */
    private fun backfillAlreadyRunningSources() {
        if (storeUnreadable) return
        val status = runCatching { controller.refreshPermissions() }.getOrNull() ?: return
        synchronized(lock) {
            val missing = bound.filter { it.sourceId !in expressed }
                .filter { b ->
                    val type = b.registration.captureForegroundType ?: return@filter false
                    capturePermissionGranted(type, status)
                }
            if (missing.isEmpty()) return
            missing.forEach {
                persisted[it.sourceId] = SourceWish.On
                expressed.add(it.sourceId)
            }
            wishStore.saveAll(persisted.toMap())
        }
    }

    /** Whether the owner has expressed a wish for this source. */
    fun isWishExpressed(sourceId: String): Boolean = synchronized(lock) { sourceId in expressed }

    override fun requiredPermissions(sourceId: String): List<String> {
        val registration = bound.firstOrNull { it.sourceId == sourceId }?.registration ?: return emptyList()
        val type = registration.captureForegroundType ?: return emptyList()
        return listOf(capturePermission(type))
    }

    override fun subscribe(listener: SourcesChangeListener): SourcesSubscription {
        synchronized(lock) { listeners.add(listener) }
        return SourcesSubscription {
            synchronized(lock) { listeners.remove(listener) }
        }
    }

    fun subscriberCount(): Int = synchronized(lock) { listeners.size }

    /**
     * Tell subscribers to re-read.
     *
     * Wishes are not the only thing that moves a source: engines start, stop, and become silenced
     * without the owner touching anything. Only [setWish] notified, so a reader that subscribed once
     * kept rendering the state as of the last toggle.
     */
    fun refreshSubscribers() {
        notifyListeners()
    }

    private fun notifyListeners() {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { listener ->
            main.post { listener.onSourcesChanged() }
        }
    }

    private inner class BoundSource(
        val registration: SourceRegistration,
    ) : ContinuousSourceEngine {
        val sourceId = registration.sourceId
        private val inner = registration.engine
        private val actuationLock = Any()
        private var sink: EmissionSink? = null
        // Believed-running: we issued start and have not confirmed a stop.
        private var started = false

        override fun start(sink: EmissionSink) {
            synchronized(actuationLock) {
                val shouldStart = synchronized(lock) {
                    this.sink = sink
                    wishes.getValue(sourceId) == SourceWish.On
                }
                if (shouldStart) {
                    try {
                        inner.start(sink)
                    } finally {
                        synchronized(lock) { started = true }
                    }
                }
            }
        }

        override fun stop() {
            synchronized(actuationLock) {
                val shouldStop = synchronized(lock) { started }
                if (shouldStop) {
                    inner.stop()
                    synchronized(lock) { started = false }
                }
                synchronized(lock) { sink = null }
            }
        }

        override fun condition(): SourceCondition {
            val wish = synchronized(lock) { wishes.getValue(sourceId) }
            return conditionFor(wish)
        }

        fun status(globalFacts: SourceFacts, permissionStatus: PermissionStatus): SourceStatus {
            val (wish, isExpressed) = synchronized(lock) {
                wishes.getValue(sourceId) to (sourceId in expressed)
            }
            val facts = if (wish == SourceWish.Off) {
                offFacts()
            } else {
                sourceFacts(globalFacts, permissionStatus)
            }.copy(wishExpressed = isExpressed)
            val (state, reason) = reduce(facts)
            return SourceStatus(
                sourceId = sourceId,
                wish = wish,
                state = state,
                reason = reason,
                wishExpressed = isExpressed,
            )
        }

        private fun conditionFor(wish: SourceWish): SourceCondition =
            inner.condition().copy(desiredOn = wish == SourceWish.On)

        private fun offFacts(): SourceFacts =
            SourceFacts(
                desiredOn = false,
                engineRunning = false,
                permissionGranted = true,
                fgsHeartbeatFresh = true,
                providerEmitting = true,
                storageOk = true,
                pairing = PairingFact.PAIRED,
                silenced = SilencedFact.UNKNOWN,
                engineStartIssued = false,
            )

        private fun sourceFacts(globalFacts: SourceFacts, permissionStatus: PermissionStatus): SourceFacts {
            val condition = runCatching { conditionFor(SourceWish.On) }.getOrNull()
            val started = synchronized(lock) { started }
            val heldTypes = ObserverForegroundService.heldCaptureForegroundTypes
            val foregroundTypeHeld = if (heldTypes != null && registration.captureForegroundType != null) {
                if (registration.captureForegroundType !in heldTypes && registration.requiredPermissionsGranted(permissionStatus)) {
                    false
                } else {
                    true
                }
            } else {
                true
            }
            // Global inputs: storageOk is shared by every desired-on row. All-required permissions,
            // FGS heartbeat, provider freshness, and pairing remain observer-only and are neutral here.
            // Per-source inputs: wish, start-issued, running, declared permissions, silenced, paused,
            // and condition health.
            return globalFacts.copy(
                desiredOn = true,
                engineRunning = condition?.running == true,
                permissionGranted = registration.requiredPermissionsGranted(permissionStatus),
                fgsHeartbeatFresh = true,
                providerEmitting = true,
                storageOk = globalFacts.storageOk,
                pairing = PairingFact.PAIRED,
                silenced = condition?.silenced ?: SilencedFact.UNKNOWN,
                engineStartIssued = started,
                conditionNeedsAttention = condition?.let { it.needsAttention || !it.available } ?: true,
                paused = condition?.paused == true,
                foregroundTypeHeld = foregroundTypeHeld,
                startRefused = controller.lastStartRefused,
            )
        }

        fun actuate(): SourceToggleResult {
            synchronized(actuationLock) {
                val current = synchronized(lock) { wishes.getValue(sourceId) }
                return try {
                    when (current) {
                        SourceWish.On -> {
                            val held = synchronized(lock) { sink }
                            if (held == null) {
                                SourceToggleResult.AwaitingObserver
                            } else {
                                val alreadyStarted = synchronized(lock) { started }
                                if (!alreadyStarted) {
                                    try {
                                        inner.start(held)
                                    } finally {
                                        synchronized(lock) { started = true }
                                    }
                                }
                                SourceToggleResult.Applied
                            }
                        }
                        SourceWish.Off -> {
                            val shouldStop = synchronized(lock) { started }
                            if (shouldStop) {
                                inner.stop()
                                synchronized(lock) { started = false }
                            }
                            SourceToggleResult.Applied
                        }
                    }
                } catch (error: Throwable) {
                    SourceToggleResult.EngineFailed(error)
                }
            }
        }
    }
}
