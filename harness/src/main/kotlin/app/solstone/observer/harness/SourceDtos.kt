// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.fgs.PermissionStatus

enum class SourceWish { Off, On }

data class ObserverStatus(
    val state: SourceState,
    val reason: ReasonCode,
    /**
     * Whether this device is paired with a journal.
     *
     * ⛔ **Do not re-derive this from [reason].** [reason] is the single highest-priority reason
     * the reducer chose, so anything above `UNPAIRED` in that order — a revoked permission, a
     * missing held type, a refused start, a stale heartbeat — masks it, and
     * `reason != ReasonCode.UNPAIRED` then reads as *paired* on a device that is not. That
     * inference shipped in 2.0.0 and put "connecting to your journal" under a `not paired` pill.
     *
     * Defaults true only so fixtures keep their existing meaning; the read model always supplies
     * the real fact.
     */
    val paired: Boolean = true,
)

data class SourceStatus(
    val sourceId: String,
    val wish: SourceWish,
    val state: SourceState,
    val reason: ReasonCode,
    /**
     * Whether the owner has expressed a wish for this source.
     *
     * ⛔ Not derivable from [wish]. An unexpressed source reports `Off` because that is what it
     * does — it is not actuated and its toggle is off — but `Off` also describes a source the owner
     * deliberately turned off, and telling those two apart is the whole point. Defaults true so a
     * fixture keeps its existing meaning.
     */
    val wishExpressed: Boolean = true,
)

data class SourcesReadModel(
    val observer: ObserverStatus,
    val sources: List<SourceStatus>,
)

data class SourceRegistration(
    val sourceId: String,
    val engine: ContinuousSourceEngine,
    val requiredPermissionsGranted: (PermissionStatus) -> Boolean = { true },
    val captureForegroundType: CaptureForegroundType? = null,
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
