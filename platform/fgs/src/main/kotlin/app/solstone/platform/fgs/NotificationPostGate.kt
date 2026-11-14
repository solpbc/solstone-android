// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

/**
 * What each notification last showed, so a refresh that would show the same thing posts nothing.
 *
 * Since Android 14 the owner can dismiss the foreground notification while capture continues, and
 * a refresh that posts unchanged content every few seconds brings it straight back. A change still
 * posts; so does the first refresh after the notification is replaced or cancelled, which is what
 * [forget] records.
 */
class NotificationPostGate {
    private val shown = HashMap<Int, String>()

    @Synchronized
    fun shouldPost(id: Int, content: String): Boolean = shown[id] != content

    @Synchronized
    fun posted(id: Int, content: String) {
        shown[id] = content
    }

    @Synchronized
    fun forget(id: Int) {
        shown.remove(id)
    }
}
