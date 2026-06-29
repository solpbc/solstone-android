// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.app.Application
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.fgs.ObserverForegroundService.ObserverServiceRehydrator

class GlassesApplication : Application() {
    lateinit var runtime: GlassesObserverRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = GlassesObserverRuntime(applicationContext)
        GlassesHarnessRuntime.runtime = runtime
        ObserverForegroundService.rehydrator = ObserverServiceRehydrator {
            runtime.rehydrateFromForegroundServiceStart()
        }
    }
}
