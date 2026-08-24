// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.observer.harness.HarnessBacklogStatus
import app.solstone.observer.scaffold.ObserverRuntimeContainer

internal object PhoneStatusSupplier {
    // Instrumented tests override this to exercise mounted loading and failed states deterministically.
    @Volatile
    internal var override: (() -> HarnessBacklogStatus)? = null

    internal fun forContainer(container: ObserverRuntimeContainer): () -> HarnessBacklogStatus =
        override ?: container.backlogStatus::read
}
