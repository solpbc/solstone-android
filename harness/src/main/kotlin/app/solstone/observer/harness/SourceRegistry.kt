// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.SourceCondition
import app.solstone.core.sources.mapSourceState

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
        bound = registrations.map { BoundSource(it.sourceId, it.engine) }
        engines = bound
    }

    override fun snapshot(): SourcesReadModel {
        val observer = controller.diagnostics().toObserverStatus()
        return SourcesReadModel(observer = observer, sources = bound.map { it.status() })
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

    private fun notifyListeners() {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { listener ->
            main.post { listener.onSourcesChanged() }
        }
    }

    private inner class BoundSource(
        val sourceId: String,
        private val inner: ContinuousSourceEngine,
    ) : ContinuousSourceEngine {
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

        fun status(): SourceStatus {
            val wish = synchronized(lock) { wishes.getValue(sourceId) }
            val condition = conditionFor(wish)
            return SourceStatus(
                sourceId = sourceId,
                wish = wish,
                state = mapSourceState(condition),
                reason = if (condition.desiredOn) ReasonCode.NONE else ReasonCode.DESIRED_OFF,
            )
        }

        private fun conditionFor(wish: SourceWish): SourceCondition =
            inner.condition().copy(desiredOn = wish == SourceWish.On)

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
