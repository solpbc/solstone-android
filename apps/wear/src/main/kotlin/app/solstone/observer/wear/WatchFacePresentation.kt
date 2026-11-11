// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import app.solstone.core.watch.courier.*

internal enum class WatchControlStyle { START, STOP, DETAILS }

/** Everything the face shows, decided in one place from facts the activity reads. */
internal data class WatchFaceModel(
    val controlLabel: String,
    val controlStyle: WatchControlStyle,
    val controlHint: String,
    /** The first screen's attention pill. Null whenever nothing needs the owner. */
    val pill: String?,
    /** The panel's lead: the time running, or the state in a few words. */
    val header: String,
    val headerSub: String?,
    val headerSpoken: String,
    val rows: List<Pair<String, String>>,
    val nextStep: String?,
    /** Small lines at the foot of the panel. The relay notice is always the last one. */
    val notes: List<String>,
)

internal data class WatchFaceFacts(
    val reduction: WatchSessionReduction,
    val engaged: Boolean,
    val elapsedMs: Long?,
    /** Sealed segments still on the watch, or null when the store could not be read. */
    val savedCount: Int?,
    val lostMarker: Boolean,
    val notificationsOff: Boolean,
    val notificationsAsked: Boolean,
    val locationOff: Boolean,
    val locationAsked: Boolean,
    val peerVersion: Int?,
    val peerContactAt: Long?,
    val nowMs: Long,
)

internal object WatchFacePresenter {
    /** The watch checks in about every 15 minutes; past this, silence is worth a word. */
    const val QUIET_PHONE_MS = 30 * 60_000L

    fun present(f: WatchFaceFacts, clock: (Long) -> String): WatchFaceModel {
        val r = f.reduction
        val failure = r.endReason != null && r.endReason != WatchSessionEndReason.OWNER_STOP
        val audioTrouble = r.state == WatchSessionState.INTERRUPTED || r.state == WatchSessionState.ATTENTION
        val phoneTooOld = f.peerVersion != null && f.peerVersion < WatchCourierProtocol.PROTOCOL_VERSION
        val watchTooOld = f.peerVersion != null && f.peerVersion > WatchCourierProtocol.PROTOCOL_VERSION
        val title = WatchSessionCopy.label(r)

        val pill = when {
            audioTrouble || failure -> title
            phoneTooOld -> "update solstone on your phone"
            watchTooOld -> "update solstone on your watch"
            else -> null
        }

        val running = f.engaged && r.state != WatchSessionState.STARTING && f.elapsedMs != null
        val header = if (running) elapsedDisplay(f.elapsedMs!! / 1000) else title
        val headerSub = if (running) (if (audioTrouble) title else "running") else null
        val headerSpoken = if (running) "${headerSub}, ${elapsedSpoken(f.elapsedMs!! / 1000)}" else title

        val heardAgo = f.peerContactAt?.let { f.nowMs - it }?.takeIf { it >= 0 }
        val rows = listOf(
            "saved on your watch" to (f.savedCount?.toString() ?: "couldn't check"),
            "phone checked in" to when {
                heardAgo == null -> "not yet"
                heardAgo < 2 * 60_000L -> "just now"
                heardAgo < 60 * 60_000L -> "${heardAgo / 60_000L} min ago"
                else -> clock(f.peerContactAt!!)
            },
        )

        val delivery = when {
            phoneTooOld -> "update solstone on your phone so what's saved can move."
            watchTooOld -> "update solstone on your watch so what's saved can move."
            heardAgo == null -> "solstone on your phone takes what your watch saves. install or open it there."
            (f.savedCount ?: 1) > 0 && heardAgo > QUIET_PHONE_MS ->
                "what's saved stays here until your phone takes it. keep your phone nearby with solstone on it."
            else -> null
        }
        val sessionStep = when (r.endReason) {
            WatchSessionEndReason.PERMISSION_FAILURE -> "allow microphone access in settings, then start again."
            WatchSessionEndReason.STORAGE_FULL -> "free space in watch settings, or open solstone on your phone so saved audio can move. then start again."
            WatchSessionEndReason.SENSOR_FAILURE -> "start again. if the microphone stops a second time, restart your watch."
            WatchSessionEndReason.PROCESS_LOSS -> "the app closed or your watch restarted. the last few minutes may be incomplete."
            else -> if (audioTrouble) "another app may be using the microphone. this session keeps trying until you stop it." else null
        }
        val nextStep = listOfNotNull(sessionStep, delivery).joinToString("\n\n").ifEmpty { null }

        val notes = buildList {
            if (f.lostMarker) add("some audio couldn't be prepared to send. what's saved stays on your watch.")
            if (delivery != null && heardAgo != null && !phoneTooOld && !watchTooOld) {
                add("if nothing moves, update solstone and Google Play services on both devices.")
            }
            if (f.notificationsOff && f.notificationsAsked) add("notifications are off, so solstone may not show on your watch face while it runs.")
            if (f.locationOff && f.locationAsked) add("location is off. sessions still work without it.")
            add(WatchCourierStrings.RELAY_NOTICE_SHORT)
        }

        val (label, style, hint) = when {
            f.engaged -> Triple("stop", WatchControlStyle.STOP, "stops the session")
            failure -> Triple("details", WatchControlStyle.DETAILS, "shows what happened")
            else -> Triple("start", WatchControlStyle.START, "starts a session")
        }
        return WatchFaceModel(label, style, hint, pill, header, headerSub, headerSpoken, rows, nextStep, notes)
    }

    fun elapsedDisplay(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        if (s < 60) return "${s}s"
        val m = s / 60
        return if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"
    }

    fun elapsedSpoken(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        if (s < 60) return if (s == 1L) "1 second" else "$s seconds"
        val m = s / 60
        if (m < 60) return if (m == 1L) "1 minute" else "$m minutes"
        val h = m / 60
        val rest = m % 60
        val hours = if (h == 1L) "1 hour" else "$h hours"
        return if (rest == 0L) hours else "$hours ${if (rest == 1L) "1 minute" else "$rest minutes"}"
    }
}
