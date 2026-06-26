// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.SourceCondition
import app.solstone.core.sources.SourceEmission

class CameraTapEngine(
    private val inner: ContinuousSourceEngine,
    private val onPhoto: (SourceEmission) -> Unit,
) : ContinuousSourceEngine {
    override fun start(sink: EmissionSink) {
        inner.start(
            EmissionSink { emission ->
                sink.emit(emission)
                if (emission.payloadRefs.isNotEmpty()) {
                    onPhoto(emission)
                }
            },
        )
    }

    override fun stop() {
        inner.stop()
    }

    override fun condition(): SourceCondition = inner.condition()
}
