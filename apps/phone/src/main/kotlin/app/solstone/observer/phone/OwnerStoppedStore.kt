// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context

/**
 * Whether the owner stopped intake themselves, as opposed to never having started it.
 *
 * 🔴 **`desiredOn = false` conflates two different things, and this is the narrowest honest way to
 * tell them apart.** *Never asked* and *asked to stop* look identical in that one boolean — the same
 * shape as the wish store's `Absent` vs `Unreadable`, and as `wishExpressed` vs a defaulted wish.
 * The consequence was concrete in both directions:
 *
 * - reading it as *never asked*, resume called `ensureObserving()` and **threw away an owner's
 *   explicit `stop intake`** — the shade said `off`, the screen said `setting up`, eight seconds
 *   apart, with nothing having asked them.
 * - reading it as *asked to stop*, resume left intake down on a fresh install whose permissions
 *   were already granted, so the migration backfill expressed the sources and nothing ever brought
 *   the service up. Sources the owner could see as on, taking nothing in.
 *
 * ⚠ Deliberately NOT a third state on `DesiredObservingStore`: that seam is shared with the watch
 * and glasses apps, and the distinction being drawn here is about a control only the phone has.
 *
 * ✅ Cleared whenever the owner asks again — turning a source on, or granting its permission — so a
 * stop is a decision about now, never a latch that makes the app harder to start later.
 */
class OwnerStoppedStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun ownerStopped(): Boolean = preferences.getBoolean(KEY_STOPPED, false)

    fun recordStopped() {
        // ⚠ `commit`: the process is being asked to stop capturing and may be killed right after.
        preferences.edit().putBoolean(KEY_STOPPED, true).commit()
    }

    fun clear() {
        preferences.edit().putBoolean(KEY_STOPPED, false).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_owner_stopped"
        const val KEY_STOPPED = "stopped"
    }
}
