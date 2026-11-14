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
 *
 * ⚠ The system drops updates an app posts in a quick burst, without telling it. So a content is
 * posted once more, by the first refresh at least [SETTLE_MS] after it was posted, and only then
 * left alone: a dropped update is put right and a dismissal is still honoured.
 */
class NotificationPostGate {
    private class Shown(val content: String, val atMs: Long, val settled: Boolean)

    private val shown = HashMap<Int, Shown>()

    @Synchronized
    fun shouldPost(id: Int, content: String, nowMs: Long): Boolean {
        val last = shown[id] ?: return true
        if (last.content != content) return true
        return !last.settled && nowMs - last.atMs >= SETTLE_MS
    }

    @Synchronized
    fun posted(id: Int, content: String, nowMs: Long) {
        val last = shown[id]
        shown[id] = Shown(content, nowMs, settled = last?.content == content)
    }

    @Synchronized
    fun forget(id: Int) {
        shown.remove(id)
    }

    companion object {
        const val SETTLE_MS = 1_000L
    }
}
