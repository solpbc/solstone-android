// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.platform.fgs.PermissionStatus

enum class SourceWish { Off, On }

data class ObserverStatus(
    val state: SourceState,
    val reason: ReasonCode,
)

data class SourceStatus(
    val sourceId: String,
    val wish: SourceWish,
    val state: SourceState,
    val reason: ReasonCode,
)

data class SourcesReadModel(
    val observer: ObserverStatus,
    val sources: List<SourceStatus>,
)

data class SourceRegistration(
    val sourceId: String,
    val engine: ContinuousSourceEngine,
    val requiredPermissionsGranted: (PermissionStatus) -> Boolean = { true },
)

sealed interface SourceToggleResult {
    data object Applied : SourceToggleResult
    data object AwaitingObserver : SourceToggleResult
    data object UnknownSource : SourceToggleResult
    data class EngineFailed(val error: Throwable) : SourceToggleResult
}

fun interface SourcesChangeListener {
    fun onSourcesChanged()
}

internal fun HarnessDiagnostics.toObserverStatus(): ObserverStatus =
    ObserverStatus(state = state, reason = reason)
