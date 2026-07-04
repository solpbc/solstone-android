// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import java.util.concurrent.Semaphore

object SyncDrainGate {
    private val permit = Semaphore(1)

    fun tryAcquire(): Boolean = permit.tryAcquire()

    fun release() {
        permit.release()
    }
}
