// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.diagnostics.StatusCue

fun commandTokenFor(action: String?, extra: String?): String? =
    if (action.isNullOrBlank()) {
        extra
    } else {
        rokidButtonActionToken(action)
    }

fun routeCommandSurfaceCommand(
    runtime: GlassesRuntimeCommands,
    rawAction: String?,
    token: String?,
    appendDiag: (String) -> Unit,
    playCue: (StatusCue) -> Unit,
): RuntimeCommandResult? {
    val result = routeDebugRuntimeCommand(runtime, null, token) ?: return null
    appendDiag(
        "command-accepted source=${commandSourceFor(rawAction)} action=${rawAction.orEmpty()} token=${token.orEmpty()}",
    )
    if (token == GlassesNotificationCommand.Stop.actionToken) {
        playCue(StatusCue.OBSERVER_PAUSED)
    }
    return result
}

private fun commandSourceFor(rawAction: String?): String =
    if (rawAction.isNullOrBlank()) "notification" else "broadcast"
