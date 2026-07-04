// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.watch

import app.solstone.observer.scaffold.ObserverAppContainer
import app.solstone.observer.scaffold.ObserverHarnessRuntime
import org.junit.Assert.fail

internal fun resetObserverRuntime() {
    ObserverHarnessRuntime.runtime?.closeForTest()
    ObserverHarnessRuntime.runtime = null
    ObserverHarnessRuntime.hooks = null
}

internal fun waitForObserverContainer(): ObserverAppContainer {
    waitUntil("observer container") { ObserverHarnessRuntime.container is ObserverAppContainer }
    return ObserverHarnessRuntime.container as ObserverAppContainer
}

internal fun waitForRecovery(container: ObserverAppContainer): Boolean {
    repeat(100) {
        if (container.recoveryCompleted) return true
        Thread.sleep(100L)
    }
    return container.recoveryCompleted
}

internal fun waitUntil(label: String, timeoutMs: Long = 10_000L, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (predicate()) return
        Thread.sleep(100)
    }
    fail("Timed out waiting for $label")
}
