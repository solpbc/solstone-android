// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.identity.JournalMarkPresentation
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

/**
 * What the shell's mark slots show: the pill at the bottom of home and the `your journal` pane.
 *
 * Known means owner-confirmed. Until the owner has answered this pairing's mark question, these
 * slots show the generic `your journal` mark, never the mark they haven't confirmed yet. The
 * prompt reads the identity coordinator itself, so it still shows the mark it is asking about.
 */
internal fun <P> shellMarkPresentation(
    currentPairing: P?,
    markGeneration: P?,
    journalConfirmed: Boolean,
    markPresentation: JournalMarkPresentation,
): JournalMarkPresentation = when {
    currentPairing != null && !journalConfirmed -> JournalMarkPresentation.Generic
    currentPairing != null && markGeneration != currentPairing -> JournalMarkPresentation.Loading
    else -> markPresentation
}
