// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import android.app.Application
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.fgs.ObserverForegroundService.ObserverServiceRehydrator
import app.solstone.platform.work.SyncScheduler

open class ObserverApplication(val spec: FormFactorSpec) : Application() {
    lateinit var runtime: ObserverRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        SyncScheduler.enqueuePeriodic(applicationContext, spec.stream)
        runtime = ObserverRuntime(applicationContext, spec)
        ObserverHarnessRuntime.runtime = runtime
        ObserverForegroundService.rehydrator = ObserverServiceRehydrator {
            runtime.rehydrateFromForegroundServiceStart()
        }
    }
}
