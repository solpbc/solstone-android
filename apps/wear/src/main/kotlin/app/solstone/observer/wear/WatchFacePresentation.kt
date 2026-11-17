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

    fun present(f: WatchFaceFacts, copy: WatchCopy, clock: (Long) -> String): WatchFaceModel {
        val r = f.reduction
        val failure = r.endReason != null && r.endReason != WatchSessionEndReason.OWNER_STOP
        val audioTrouble = r.state == WatchSessionState.INTERRUPTED || r.state == WatchSessionState.ATTENTION
        val phoneTooOld = f.peerVersion != null && f.peerVersion < WatchCourierProtocol.PROTOCOL_VERSION
        val watchTooOld = f.peerVersion != null && f.peerVersion > WatchCourierProtocol.PROTOCOL_VERSION
        val title = WatchSessionCopy.label(r, copy)

        val pill = when {
            audioTrouble || failure -> title
            phoneTooOld -> copy.text(WatchMessage.UPDATE_SOLSTONE_ON_YOUR_PHONE)
            watchTooOld -> copy.text(WatchMessage.UPDATE_SOLSTONE_ON_YOUR_WATCH)
            else -> null
        }

        val running = f.engaged && r.state != WatchSessionState.STARTING && f.elapsedMs != null
        val header = if (running) elapsedDisplay(f.elapsedMs!! / 1000, copy) else title
        val headerSub = if (running) (if (audioTrouble) title else copy.text(WatchMessage.RUNNING)) else null
        val headerSpoken = if (running) copy.text(WatchMessage.HEADER_SPOKEN, headerSub!!, elapsedSpoken(f.elapsedMs!! / 1000, copy)) else title

        val heardAgo = f.peerContactAt?.let { f.nowMs - it }?.takeIf { it >= 0 }
        val rows = listOf(
            copy.text(WatchMessage.HELD_ON_YOUR_WATCH) to (f.savedCount?.let { copy.text(WatchMessage.COUNT, copy.number(it.toLong())) } ?: copy.text(WatchMessage.COULDN_T_CHECK)),
            copy.text(WatchMessage.PHONE_CHECKED_IN) to when {
                heardAgo == null -> copy.text(WatchMessage.NOT_YET)
                heardAgo < 2 * 60_000L -> copy.text(WatchMessage.JUST_NOW)
                heardAgo < 60 * 60_000L -> copy.text(WatchMessage.MINUTES_AGO, copy.number(heardAgo / 60_000L))
                else -> clock(f.peerContactAt!!)
            },
        )

        val delivery = when {
            phoneTooOld -> copy.text(WatchMessage.UPDATE_SOLSTONE_ON_YOUR_PHONE_SO_WHAT_S_SAVED_CAN_MOVE)
            watchTooOld -> copy.text(WatchMessage.UPDATE_SOLSTONE_ON_YOUR_WATCH_SO_WHAT_S_SAVED_CAN_MOVE)
            heardAgo == null -> copy.text(WatchMessage.SOLSTONE_ON_YOUR_PHONE_TAKES_WHAT_YOUR_WATCH_SAVES_INSTALL_OR_OPEN_IT_THERE)
            (f.savedCount ?: 1) > 0 && heardAgo > QUIET_PHONE_MS ->
                copy.text(WatchMessage.WHAT_S_SAVED_STAYS_HERE_UNTIL_YOUR_PHONE_TAKES_IT_KEEP_YOUR_PHONE_NEARBY_WITH_SOLSTONE_ON_IT)
            else -> null
        }
        val sessionStep = when (r.endReason) {
            WatchSessionEndReason.PERMISSION_FAILURE -> copy.text(WatchMessage.ALLOW_MICROPHONE_ACCESS_IN_SETTINGS_THEN_START_AGAIN)
            WatchSessionEndReason.STORAGE_FULL -> copy.text(WatchMessage.FREE_SPACE_IN_WATCH_SETTINGS_OR_OPEN_SOLSTONE_ON_YOUR_PHONE_SO_SAVED_AUDIO_CAN_MOVE_THEN_START_AGAIN)
            WatchSessionEndReason.SENSOR_FAILURE -> copy.text(WatchMessage.START_AGAIN_IF_THE_MICROPHONE_STOPS_A_SECOND_TIME_RESTART_YOUR_WATCH)
            WatchSessionEndReason.PROCESS_LOSS -> copy.text(WatchMessage.THE_APP_CLOSED_OR_YOUR_WATCH_RESTARTED_THE_LAST_FEW_MINUTES_MAY_BE_INCOMPLETE)
            else -> if (audioTrouble) copy.text(WatchMessage.ANOTHER_APP_MAY_BE_USING_THE_MICROPHONE_THIS_SESSION_KEEPS_TRYING_UNTIL_YOU_STOP_IT) else null
        }
        val nextStep = if (sessionStep != null && delivery != null) copy.text(WatchMessage.NEXT_STEPS, sessionStep, delivery) else sessionStep ?: delivery

        val notes = buildList {
            if (f.lostMarker) add(copy.text(WatchMessage.SOME_AUDIO_COULDN_T_BE_PREPARED_TO_SEND_WHAT_S_SAVED_STAYS_ON_YOUR_WATCH))
            if (delivery != null && heardAgo != null && !phoneTooOld && !watchTooOld) {
                add(copy.text(WatchMessage.IF_NOTHING_MOVES_UPDATE_SOLSTONE_AND_GOOGLE_PLAY_SERVICES_ON_BOTH_DEVICES))
            }
            if (f.notificationsOff && f.notificationsAsked) add(copy.text(WatchMessage.NOTIFICATIONS_ARE_OFF_SO_SOLSTONE_MAY_NOT_SHOW_ON_YOUR_WATCH_FACE_WHILE_IT_RUNS))
            if (f.locationOff && f.locationAsked) add(copy.text(WatchMessage.LOCATION_IS_OFF_SESSIONS_STILL_WORK_WITHOUT_IT))
            add(copy.text(WatchMessage.RELAY_NOTICE_SHORT))
        }

        val (label, style, hint) = when {
            f.engaged -> Triple(copy.text(WatchMessage.STOP), WatchControlStyle.STOP, copy.text(WatchMessage.STOPS_THE_SESSION))
            failure -> Triple(copy.text(WatchMessage.DETAILS), WatchControlStyle.DETAILS, copy.text(WatchMessage.SHOWS_WHAT_HAPPENED))
            else -> Triple(copy.text(WatchMessage.START), WatchControlStyle.START, copy.text(WatchMessage.STARTS_A_SESSION))
        }
        return WatchFaceModel(label, style, hint, pill, header, headerSub, headerSpoken, rows, nextStep, notes)
    }

    fun elapsedDisplay(seconds: Long, copy: WatchCopy): String {
        val s = seconds.coerceAtLeast(0)
        if (s < 60) return copy.text(WatchMessage.SECONDS_SHORT, copy.number(s))
        val m = s / 60
        return if (m < 60) copy.text(WatchMessage.MINUTES_SHORT, copy.number(m))
        else copy.text(WatchMessage.HOURS_MINUTES_SHORT, copy.number(m / 60), copy.number(m % 60))
    }

    fun elapsedSpoken(seconds: Long, copy: WatchCopy): String {
        val s = seconds.coerceAtLeast(0)
        if (s < 60) return copy.quantity(WatchMessage.SECONDS_SPOKEN, s.toInt(), copy.number(s))
        val m = s / 60
        if (m < 60) return copy.quantity(WatchMessage.MINUTES_SPOKEN, m.toInt(), copy.number(m))
        val h = m / 60
        val rest = m % 60
        val hours = copy.quantity(WatchMessage.HOURS_SPOKEN, h.toInt(), copy.number(h))
        return if (rest == 0L) hours else copy.text(WatchMessage.HOURS_MINUTES_SPOKEN,
            hours, copy.quantity(WatchMessage.MINUTES_SPOKEN, rest.toInt(), copy.number(rest)))
    }
}
