// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import android.content.Context

class ObserverRuntime(
    private val appContext: Context?,
    private val spec: FormFactorSpec,
    private val containerFactory: (Context, FormFactorSpec) -> ObserverAppContainer = ::ObserverAppContainer,
) {
    private val lock = Any()
    private var container: ObserverAppContainer? = null
    private var testContainer: ObserverRuntimeContainer? = null
    private var containerInitializedListener: ((ObserverRuntimeContainer) -> Unit)? = null

    internal constructor(container: ObserverRuntimeContainer, spec: FormFactorSpec) : this(appContext = null, spec = spec) {
        testContainer = container
    }

    val containerIfInitialized: ObserverRuntimeContainer?
        get() = synchronized(lock) { container ?: testContainer }

    fun container(): ObserverAppContainer {
        var created: ObserverAppContainer? = null
        val result = synchronized(lock) {
            val context = requireNotNull(appContext) { "appContext is required for a real ObserverAppContainer" }
            container ?: containerFactory(context, spec).also {
                container = it
                created = it
            }
        }
        created?.let { initialized -> containerInitializedListener?.invoke(initialized) }
        return result
    }

    fun onContainerInitialized(listener: (ObserverRuntimeContainer) -> Unit) {
        val initialized = synchronized(lock) {
            containerInitializedListener = listener
            container ?: testContainer
        }
        initialized?.let(listener)
    }

    fun closeForTest() {
        val containers = synchronized(lock) {
            val current = container
            val test = testContainer
            container = null
            testContainer = null
            current to test
        }
        containers.first?.close()
        containers.second?.close()
    }

    fun rehydrateFromForegroundServiceStart() {
        runtimeContainer().rehydrateInBackground()
    }

    private fun runtimeContainer(): ObserverRuntimeContainer =
        synchronized(lock) { testContainer } ?: container()
}

object ObserverHarnessRuntime {
    @Volatile var runtime: ObserverRuntime? = null
    val container: ObserverRuntimeContainer?
        get() = runtime?.containerIfInitialized
    var hooks: ObserverRuntimeHooks? = null
}

class ObserverRuntimeHooks {
    @Volatile var onRecoveryComplete: (() -> Unit)? = null
    @Volatile var onEvidenceLoadComplete: (() -> Unit)? = null
    @Volatile var onSyncLoadComplete: (() -> Unit)? = null
    @Volatile var onJournalCacheLoadComplete: (() -> Unit)? = null
}
