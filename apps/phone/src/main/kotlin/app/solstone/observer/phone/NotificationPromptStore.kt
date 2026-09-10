// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context

/**
 * Whether this install has already had its one notification prompt.
 *
 * 🔴 **This was an in-memory field on the activity, and the comment above it claimed
 * *"asked once, and never re-asked"* — which the mechanism did not deliver.** Kill the app,
 * reopen it, turn a source on, and android 13+ shows the dialog again. ⚠ Nothing surfaced it,
 * because *"once"* is true of a single process and every test ran inside one; it takes a process
 * death between the two asks, which is exactly what an owner does and a test does not.
 *
 * ⛔ *Once* means once per install, so the flag has to outlive the process. It records that the
 * prompt was SHOWN, not what the owner answered: a denial is a decision, and re-asking is how an
 * app argues with one.
 */
class NotificationPromptStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun wasAsked(): Boolean = preferences.getBoolean(KEY_ASKED, false)

    fun recordAsked() {
        // ⚠ `commit`, not `apply`: the very next thing after this is a system dialog that can take
        // the process down with it, and an unflushed write would ask again on the way back.
        preferences.edit().putBoolean(KEY_ASKED, true).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_notification_prompt"
        const val KEY_ASKED = "asked"
    }
}
