// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.watch

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import app.solstone.observer.scaffold.ObserverAppContainer
import app.solstone.observer.scaffold.ObserverHarnessRuntime
import org.junit.Assert.fail

internal const val TEST_DATABASE_NAME = "solstone-persistence.db"

internal fun resetObserverRuntime() {
    ObserverHarnessRuntime.runtime?.closeForTest()
    ObserverHarnessRuntime.runtime = null
    ObserverHarnessRuntime.hooks = null
}

internal fun resetPersistence(context: Context) {
    context.deleteDatabase(TEST_DATABASE_NAME)
    context.filesDir.resolve("spool").deleteRecursively()
    context.filesDir.resolve("journal-cache-limit").delete()
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

internal fun collectTexts(root: View): List<String> = buildList {
    fun visit(view: View) {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
    }
    visit(root)
}

internal fun clickButton(root: View, label: String) {
    fun visit(view: View): Boolean {
        if (view is Button && view.text.toString() == label) return view.performClick().let { true }
        if (view is ViewGroup) for (i in 0 until view.childCount) if (visit(view.getChildAt(i))) return true
        return false
    }
    check(visit(root)) { "button not found: $label" }
}
