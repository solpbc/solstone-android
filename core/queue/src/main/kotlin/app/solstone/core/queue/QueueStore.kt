// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.queue

import app.solstone.core.model.QueueState

interface QueueStore {
    fun advance(segmentId: String, event: QueueEvent): QueueState
}
