// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.power

import android.content.Context

interface AutostartConfirmationStore {
    fun isAutostartConfirmed(): Boolean
    fun setAutostartConfirmed(confirmed: Boolean)
}

class SharedPreferencesAutostartConfirmationStore(
    context: Context,
    name: String = "solstone_power",
) : AutostartConfirmationStore {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun isAutostartConfirmed(): Boolean =
        preferences.getBoolean(KEY_AUTOSTART_CONFIRMED, false)

    override fun setAutostartConfirmed(confirmed: Boolean) {
        preferences.edit().putBoolean(KEY_AUTOSTART_CONFIRMED, confirmed).apply()
    }

    private companion object {
        const val KEY_AUTOSTART_CONFIRMED = "autostart_confirmed"
    }
}
