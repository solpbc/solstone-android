// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

object JournalConfirmationPolicy {
    @Volatile
    var consults: Boolean = true

    fun optOut() {
        consults = false
    }
}
