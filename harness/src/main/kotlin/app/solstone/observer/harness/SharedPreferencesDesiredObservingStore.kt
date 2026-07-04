// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import android.content.Context
import app.solstone.platform.fgs.ObserverRuntimePrefs

class SharedPreferencesDesiredObservingStore(
    context: Context,
    name: String = ObserverRuntimePrefs.PREFS_NAME,
) : DesiredObservingStore {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun isDesiredOn(): Boolean =
        preferences.getBoolean(ObserverRuntimePrefs.KEY_DESIRED_ON, false)

    override fun setDesiredOn(on: Boolean) {
        preferences.edit().putBoolean(ObserverRuntimePrefs.KEY_DESIRED_ON, on).apply()
    }
}
