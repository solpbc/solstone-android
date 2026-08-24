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
}

class SourceRegistry(
    private val controller: HarnessController,
    registrations: List<SourceRegistration>,
    private val main: MainPoster,
    private val wishStore: SourceWishStore,
) : SourcesReader {
    private val lock = Any()
    private val wishes = LinkedHashMap<String, SourceWish>()
    private val bound: List<BoundSource>
    private val listeners = mutableListOf<SourcesChangeListener>()

    val engines: List<ContinuousSourceEngine>

    init {
        require(registrations.all { it.sourceId.isNotBlank() }) { "sourceId must be non-blank" }
        val ids = registrations.map { it.sourceId }
        require(ids.size == ids.toSet().size) { "sourceId values must be unique" }
        val persisted = wishStore.loadAll()
        registrations.forEach { wishes[it.sourceId] = persisted[it.sourceId] ?: SourceWish.On }
        bound = registrations.map(::BoundSource)
        engines = bound
    }

    override fun snapshot(): SourcesReadModel {
        val inputs = controller.globalFactInputs()
        val globalFacts = sourceFactsFor(inputs)
        val (state, reason) = reduce(globalFacts)
        return SourcesReadModel(
            observer = ObserverStatus(state = state, reason = reason),
            sources = bound.map { it.status(globalFacts, inputs.permissionStatus) },
        )
    }

    override fun setWish(sourceId: String, wish: SourceWish): SourceToggleResult {
        val wrapper = synchronized(lock) {
            if (sourceId !in wishes) return SourceToggleResult.UnknownSource
            wishes[sourceId] = wish
            wishStore.saveAll(wishes.toMap())
            bound.first { it.sourceId == sourceId }
        }
        val result = wrapper.actuate()
        notifyListeners()
        return result
    }

    override fun subscribe(listener: SourcesChangeListener): SourcesSubscription {
        synchronized(lock) { listeners.add(listener) }
        return SourcesSubscription {
            synchronized(lock) { listeners.remove(listener) }
        }
    }

    fun subscriberCount(): Int = synchronized(lock) { listeners.size }

    private fun notifyListeners() {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { listener ->
            main.post { listener.onSourcesChanged() }
        }
    }

    private inner class BoundSource(
        private val registration: SourceRegistration,
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
            val wish = synchronized(lock) { wishes.getValue(sourceId) }
            val facts = if (wish == SourceWish.Off) {
                offFacts()
            } else {
                sourceFacts(globalFacts, permissionStatus)
            }
            val (state, reason) = reduce(facts)
            return SourceStatus(
                sourceId = sourceId,
                wish = wish,
                state = state,
                reason = reason,
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
