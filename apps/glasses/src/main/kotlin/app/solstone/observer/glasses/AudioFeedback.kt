// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.diagnostics.StatusCue

fun interface AudioFeedback {
    fun play(cue: StatusCue)
}
