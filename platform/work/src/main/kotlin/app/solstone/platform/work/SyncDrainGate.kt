// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import java.util.concurrent.Semaphore

/**
 * The one permit every send to the journal and every pairing attempt takes.
 *
 * ⚠ [closed] is the app's own hold on both. While it reads true no new permit is handed out, so a
 * sync run returns before it reads a credential and a pair attempt never dials. An app that installs
 * nothing keeps the gate open, exactly as before.
 */
object SyncDrainGate {
    private val permit = Semaphore(1)

    @Volatile var closed: () -> Boolean = { false }

    fun tryAcquire(): Boolean = !closed() && permit.tryAcquire()

    fun release() {
        permit.release()
    }

    /** Whether a drain or a pair attempt holds the permit right now. */
    fun inUse(): Boolean = permit.availablePermits() == 0
}
