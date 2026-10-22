// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SilencedFact
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.SourceCondition

/**
 * Calendar does not capture through the observer service. Start and stop do not query, emit, or
 * touch a service. The condition may report running; the wish still decides the tile.
 */
class InertCalendarEngine(
    private val storageOk: () -> Boolean,
    private val needsAttention: () -> Boolean = { false },
) : ContinuousSourceEngine {
    override fun start(sink: EmissionSink) = Unit

    override fun stop() = Unit

    override fun condition(): SourceCondition = SourceCondition(
        desiredOn = false,
        running = true,
        available = true,
        needsAttention = needsAttention(),
        paused = false,
        silenced = SilencedFact.NOT_SILENCED,
    )

    fun reasonWhenOff(): ReasonCode? = if (storageOk()) null else ReasonCode.STORAGE_FULL
}
