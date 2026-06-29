// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

object RokidButtonActions {
    const val CLICK = "com.rokid.glass3.action.button.CLICK"
    const val DOUBLE_CLICK = "com.rokid.glass3.action.button.DOUBLE_CLICK"
    const val DOWN = "com.rokid.glass3.action.button.DOWN"
    const val UP = "com.rokid.glass3.action.button.UP"
    const val LONG_PRESS = "com.rokid.glass3.action.button.LONG_PRESS"
    val all = listOf(CLICK, DOUBLE_CLICK, DOWN, UP, LONG_PRESS)
}

// ASSUMED/UNVERIFIED Rokid synthetic/ordered broadcast strings only. This makes no
// claim about physical button ownership; malformed or unknown actions are ignored.
fun rokidButtonActionToken(action: String?): String? =
    when (action) {
        RokidButtonActions.CLICK -> GlassesNotificationCommand.Status.actionToken
        RokidButtonActions.DOUBLE_CLICK -> "observe_start"
        RokidButtonActions.LONG_PRESS -> GlassesNotificationCommand.Stop.actionToken
        else -> null
    }

fun rokidButtonHandlingEnabled(guidanceId: String): Boolean = guidanceId == "rokid"
