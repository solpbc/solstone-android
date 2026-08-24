// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import android.content.Context

internal interface PhoneHomeTileStore {
    fun hasTile(sourceId: String): Boolean

    fun setHasTile(sourceId: String, hasTile: Boolean)
}

internal class SharedPreferencesPhoneHomeTileStore(
    context: Context,
    name: String = "phone-home-tiles",
) : PhoneHomeTileStore {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun hasTile(sourceId: String): Boolean = preferences.getBoolean(key(sourceId), false)

    override fun setHasTile(sourceId: String, hasTile: Boolean) {
        preferences.edit().putBoolean(key(sourceId), hasTile).apply()
    }

    private fun key(sourceId: String): String = "tile-on-home.$sourceId"
}
