// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.identity.GraphMutationResult
import app.solstone.platform.work.JournalRevokeOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal suspend fun leaveJournal(
    dispatcher: CoroutineDispatcher,
    revoke: () -> JournalRevokeOutcome,
    forget: () -> GraphMutationResult,
    log: (String) -> Unit,
): GraphMutationResult = withContext(dispatcher) {
    val outcome = try {
        revoke()
    } catch (_: Throwable) {
        JournalRevokeOutcome.UNREACHED
    }
    log("kind=unpair revoke=${outcome.name.lowercase()}")
    forget()
}
