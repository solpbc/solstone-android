// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

fun interface ForegroundStartAllowed {
    fun isForegroundStartAllowed(): Boolean
}

/**
 * Conservative until real Android 14+ while-in-use/background foreground-service start detection exists.
 * Visible manual starts do not depend on this gate.
 */
class AndroidForegroundStartAllowed : ForegroundStartAllowed {
    override fun isForegroundStartAllowed(): Boolean = false
}
