// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Intent
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.pl.browser.JournalBrowserLifecycleListener
import app.solstone.core.pl.browser.JournalBrowserOrigin
import app.solstone.core.pl.browser.JournalBrowserSession

internal interface JournalSheetSession {
    fun addLifecycleListener(listener: JournalBrowserLifecycleListener)
    fun removeLifecycleListener(listener: JournalBrowserLifecycleListener)
    fun start(): JournalBrowserOrigin
    fun stop()
}

internal class LiveJournalSheetSession(
    private val session: JournalBrowserSession,
) : JournalSheetSession {
    override fun addLifecycleListener(listener: JournalBrowserLifecycleListener) = session.addLifecycleListener(listener)
    override fun removeLifecycleListener(listener: JournalBrowserLifecycleListener) = session.removeLifecycleListener(listener)
    override fun start(): JournalBrowserOrigin = session.start()
    override fun stop() = session.stop()
}

internal object PhoneJournalTestHooks {
    @Volatile var pairingSnapshotOverride: PairingGraphSnapshot? = null
    @Volatile var sessionOverride: (() -> JournalSheetSession)? = null
    @Volatile var onLoadUrl: ((String) -> Unit)? = null
    @Volatile var onVisitedHistory: ((url: String, canGoBack: Boolean) -> Unit)? = null
    @Volatile var externalViewStarter: ((Intent) -> Unit)? = null

    fun reset() {
        pairingSnapshotOverride = null
        sessionOverride = null
        onLoadUrl = null
        onVisitedHistory = null
        externalViewStarter = null
    }
}
