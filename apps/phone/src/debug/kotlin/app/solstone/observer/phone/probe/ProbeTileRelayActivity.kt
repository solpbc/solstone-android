// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.probe

import android.app.Activity
import android.os.Bundle

/**
 * Transient visible activity for tile mode 2. Starts the FGS from a resumed activity, then finishes.
 * Drive: tile mode=relay, fully background the app, tap the tile.
 *   open index: adb shell am start -n app.solstone.observer.phone/app.solstone.observer.phone.probe.ProbeIndexActivity
 */
class ProbeTileRelayActivity : Activity() {
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeLog.install(filesDir)
    }

    override fun onResume() {
        super.onResume()
        if (started) return
        started = true
        Probe2Starts.startAndClassify(this, Probe2Starts.mode(this))
        finish()
    }
}
