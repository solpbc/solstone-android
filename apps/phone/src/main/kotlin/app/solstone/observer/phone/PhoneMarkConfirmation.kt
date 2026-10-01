// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.identity.awaitingMarkConfirmation
import app.solstone.platform.work.syncStores

internal fun phoneAwaitingMarkConfirmation(context: Context): Boolean {
    val stores = syncStores(context)
    return awaitingMarkConfirmation(
        consults = JournalConfirmationPolicy.consults,
        snapshot = stores.publisher.currentSnapshot(),
        store = stores.journalConfirmationStore,
    )
}
