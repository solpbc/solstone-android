// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

interface GlassesRuntimeCommands {
    fun observeStart(): RuntimeCommandResult
    fun observeStop(): RuntimeCommandResult
    fun syncNow(): RuntimeCommandResult
    fun pairLink(raw: String): RuntimeCommandResult
    fun speakStatus(): RuntimeCommandResult
    fun speakNeedsAttention(): RuntimeCommandResult
}

fun routeDebugRuntimeCommand(
    runtime: GlassesRuntimeCommands,
    pairLink: String?,
    action: String?,
): RuntimeCommandResult? =
    when {
        !pairLink.isNullOrBlank() -> runtime.pairLink(pairLink)
        action == "observe_start" -> runtime.observeStart()
        action == "observe_stop" -> runtime.observeStop()
        action == "sync_now" -> runtime.syncNow()
        action == "status" || action == "speak_status" -> runtime.speakStatus()
        action == "speak_needs_attention" -> runtime.speakNeedsAttention()
        else -> null
    }
