// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

/**
 * One recovery pass. Each step is caught on its own, so a failure does not skip the steps after it.
 * [passEnded] means the pass finished and was not cancelled. It does not depend on the gap store.
 * [noticesMayShow] is set only when [openNotices] returns true after that. Glasses pass null and
 * never open a notice. A cancelled pass sets neither flag and stops before the remaining steps.
 */
class RecoveryPass(
    private val cancelled: () -> Boolean = { false },
) {
    var passEnded: Boolean = false
        private set
    var noticesMayShow: Boolean = false
        private set

    fun run(steps: List<() -> Unit>, openNotices: (() -> Boolean)? = null) {
        for (step in steps) {
            if (cancelled()) return
            runCatching { step() }
        }
        if (cancelled()) return
        val opened = if (openNotices == null) {
            false
        } else {
            runCatching { openNotices() }.getOrDefault(false)
        }
        if (cancelled()) return
        passEnded = true
        noticesMayShow = openNotices != null && opened
    }
}
