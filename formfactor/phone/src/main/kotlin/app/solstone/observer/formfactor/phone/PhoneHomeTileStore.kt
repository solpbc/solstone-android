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

    /**
     * ⚠ **Defaults to true.** A source the owner has is on home until they hide it —
     * `give this a tile on home` is a visibility control, and § 5 is explicit that
     * hiding never turns a source off. The default was `false` while nothing read this
     * store, so flipping the deck to honour it on a `false` default would have shipped
     * an empty deck.
     */
    override fun hasTile(sourceId: String): Boolean = preferences.getBoolean(key(sourceId), true)

    override fun setHasTile(sourceId: String, hasTile: Boolean) {
        preferences.edit().putBoolean(key(sourceId), hasTile).apply()
    }

    private fun key(sourceId: String): String = "tile-on-home.$sourceId"
}
