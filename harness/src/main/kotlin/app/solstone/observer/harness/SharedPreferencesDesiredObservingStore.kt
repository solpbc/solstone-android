// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import android.content.Context

class SharedPreferencesDesiredObservingStore(
    context: Context,
    name: String = "solstone_observer_runtime",
) : DesiredObservingStore {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun isDesiredOn(): Boolean =
        preferences.getBoolean(KEY_DESIRED_OBSERVING_ON, false)

    override fun setDesiredOn(on: Boolean) {
        preferences.edit().putBoolean(KEY_DESIRED_OBSERVING_ON, on).apply()
    }

    private companion object {
        const val KEY_DESIRED_OBSERVING_ON = "desired_observing_on"
    }
}
