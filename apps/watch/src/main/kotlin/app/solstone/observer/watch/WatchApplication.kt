// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.watch

import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.observer.scaffold.ObserverApplication

class WatchApplication : ObserverApplication(watchSpec) {
    override fun onCreate() {
        JournalConfirmationPolicy.optOut()
        super.onCreate()
    }
}
