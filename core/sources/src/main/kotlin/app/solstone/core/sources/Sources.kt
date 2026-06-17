// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.sources

import app.solstone.core.model.GapEvent
import app.solstone.core.model.SourceKind
import app.solstone.core.model.SourceState

data class SourceEmission(
  val sourceId: String,
  val stream: String,
  val sourceKind: SourceKind,
  val captureStartEpochMs: Long,
  val captureEndEpochMs: Long,
  val payloadRefs: List<PayloadRef>,
  val metadata: Map<String, String>,
  val gaps: List<GapEvent>,
)

const val MAIN_STREAM = "observer"
const val LOCATION_STREAM = "location"

data class PayloadRef(val name: String, val mediaType: String, val byteSize: Long, val sha256: String?)

fun interface EmissionSink {
    fun emit(emission: SourceEmission)
}

interface ContinuousSourceEngine {
    fun start(sink: EmissionSink)
    fun stop()
    fun condition(): SourceCondition
}

interface ImportSourceHandler {
    fun importNow(): List<SourceEmission>
}

data class SourceCondition(
    val desiredOn: Boolean,
    val running: Boolean,
    val available: Boolean,
    val needsAttention: Boolean,
    val paused: Boolean,
)

fun mapSourceState(condition: SourceCondition): SourceState =
    when {
        !condition.desiredOn -> SourceState.OFF
        condition.needsAttention || !condition.available -> SourceState.NEEDS_ATTENTION
        condition.paused -> SourceState.PAUSED
        condition.running -> SourceState.ON
        else -> SourceState.SETTING_UP
    }
