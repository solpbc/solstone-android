// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context

class CapturePermissionRequestStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun wasRequested(sourceId: String): Boolean =
        preferences.getBoolean(KEY_REQUESTED_PREFIX + sourceId, false)

    fun recordRequested(sourceId: String) {
        preferences.edit().putBoolean(KEY_REQUESTED_PREFIX + sourceId, true).commit()
    }

    fun recordAwaiting(sourceId: String, priorWish: PriorSourceWish) {
        preferences.edit()
            .putString(KEY_AWAITING_SOURCE_ID, sourceId)
            .putString(KEY_AWAITING_PRIOR_WISH, priorWish.name)
            .commit()
    }

    data class AwaitingRequest(val sourceId: String, val priorWish: PriorSourceWish)

    fun readAwaiting(): AwaitingRequest? {
        val id = preferences.getString(KEY_AWAITING_SOURCE_ID, null) ?: return null
        val priorName = preferences.getString(KEY_AWAITING_PRIOR_WISH, null) ?: return null
        val prior = runCatching { PriorSourceWish.valueOf(priorName) }.getOrDefault(PriorSourceWish.Unexpressed)
        return AwaitingRequest(id, prior)
    }

    fun clearAwaiting() {
        preferences.edit()
            .remove(KEY_AWAITING_SOURCE_ID)
            .remove(KEY_AWAITING_PRIOR_WISH)
            .commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_capture_permission_requests"
        const val KEY_REQUESTED_PREFIX = "requested."
        const val KEY_AWAITING_SOURCE_ID = "awaiting_source_id"
        const val KEY_AWAITING_PRIOR_WISH = "awaiting_prior_wish"
    }
}
