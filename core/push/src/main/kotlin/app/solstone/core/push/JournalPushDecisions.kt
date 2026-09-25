// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.push

private const val REPAIR_CAP_MILLIS = 60_000L

fun validJournalOpenPath(raw: String?): String? {
    if (raw == null || !raw.startsWith("/app/")) return null
    if (Regex("^/[A-Za-z0-9._~/-]*$").matchEntire(raw) == null) return null
    if ("//" in raw) return null
    if (raw.split('/').any { it == "." || it == ".." }) return null
    return raw
}

fun journalOpenPath(
    pushRegistration: Boolean,
    freshCreate: Boolean,
    launchedFromHistory: Boolean,
    pairingCommitted: Boolean,
    rawPath: String?,
): String? {
    if (!pushRegistration || !freshCreate || launchedFromHistory || !pairingCommitted) {
        return null
    }
    return validJournalOpenPath(rawPath)
}

sealed interface JournalNotificationRow {
    data object On : JournalNotificationRow
    data object NeedsDeliveryApp : JournalNotificationRow
    data object ChooseDeliveryApp : JournalNotificationRow
    data object InsecureAddress : JournalNotificationRow
    data class DeliveryAppStopped(val appName: String) : JournalNotificationRow
}

// An unanswering app that is not stopped shows nothing.
fun journalNotificationRow(
    state: PushDeliveryState,
    notificationsAllowed: Boolean,
    journalChannelBlocked: Boolean,
    distributorPackages: Set<String>?,
    ownPackage: String,
    appLabel: String?,
    stopped: Boolean?,
): JournalNotificationRow? = when (state) {
    PushDeliveryState.Ready -> {
        if (notificationsAllowed && !journalChannelBlocked) {
            JournalNotificationRow.On
        } else {
            null
        }
    }
    PushDeliveryState.NoDeliveryApp -> {
        if (distributorPackages != null && distributorPackages.all { it == ownPackage }) {
            JournalNotificationRow.NeedsDeliveryApp
        } else {
            null
        }
    }
    PushDeliveryState.ChooseDeliveryApp -> JournalNotificationRow.ChooseDeliveryApp
    PushDeliveryState.InsecureAddress -> JournalNotificationRow.InsecureAddress
    is PushDeliveryState.WaitingForDelivery -> {
        if (state.unanswered && state.distributorPackage != ownPackage && stopped == true && appLabel != null) {
            JournalNotificationRow.DeliveryAppStopped(appLabel)
        } else {
            null
        }
    }
    PushDeliveryState.JournalHasNoPush,
    PushDeliveryState.NotLinked,
    is PushDeliveryState.Failed,
    PushDeliveryState.Off -> null
}

sealed interface JournalPushRepairAction {
    data object None : JournalPushRepairAction
    data object Enqueue : JournalPushRepairAction
    data object Reregister : JournalPushRepairAction
}

data class JournalPushRepairMemory(
    val distributorPackages: Set<String>? = null,
    val lastReregisterAtMillis: Long? = null,
)

data class JournalPushRepairDecision(
    val action: JournalPushRepairAction,
    val memory: JournalPushRepairMemory,
)

fun journalPushRepair(
    pushRegistration: Boolean,
    pairingCommitted: Boolean,
    state: PushDeliveryState,
    nowMillis: Long,
    memory: JournalPushRepairMemory,
    ownPackage: String,
    readDistributors: () -> Set<String>,
    stopped: (packageName: String) -> Boolean?,
): JournalPushRepairDecision {
    if (!pushRegistration || !pairingCommitted) {
        return JournalPushRepairDecision(JournalPushRepairAction.None, memory)
    }

    val currentDistributors = readDistributors()
    val nextMemory = memory.copy(distributorPackages = currentDistributors)

    fun tryReregister(): JournalPushRepairDecision {
        val last = memory.lastReregisterAtMillis
        return if (last == null || nowMillis - last >= REPAIR_CAP_MILLIS) {
            JournalPushRepairDecision(
                JournalPushRepairAction.Reregister,
                nextMemory.copy(lastReregisterAtMillis = nowMillis),
            )
        } else {
            JournalPushRepairDecision(JournalPushRepairAction.None, nextMemory)
        }
    }

    return when (state) {
        PushDeliveryState.NoDeliveryApp,
        PushDeliveryState.ChooseDeliveryApp -> {
            JournalPushRepairDecision(JournalPushRepairAction.Enqueue, nextMemory)
        }
        PushDeliveryState.Ready -> {
            if (memory.distributorPackages != null && memory.distributorPackages != currentDistributors) {
                JournalPushRepairDecision(JournalPushRepairAction.Enqueue, nextMemory)
            } else {
                JournalPushRepairDecision(JournalPushRepairAction.None, nextMemory)
            }
        }
        is PushDeliveryState.WaitingForDelivery -> {
            if (state.unanswered && state.distributorPackage != ownPackage) {
                val isStopped = stopped(state.distributorPackage)
                if (isStopped == false) {
                    tryReregister()
                } else {
                    JournalPushRepairDecision(JournalPushRepairAction.None, nextMemory)
                }
            } else {
                JournalPushRepairDecision(JournalPushRepairAction.None, nextMemory)
            }
        }
        PushDeliveryState.InsecureAddress -> tryReregister()
        PushDeliveryState.JournalHasNoPush,
        PushDeliveryState.NotLinked,
        is PushDeliveryState.Failed,
        PushDeliveryState.Off -> JournalPushRepairDecision(JournalPushRepairAction.None, nextMemory)
    }
}

sealed interface JournalPushPickerAction {
    data class StorePick(val packageName: String) : JournalPushPickerAction
    data object Enqueue : JournalPushPickerAction
}

fun journalPushPickerResult(success: Boolean, savedDistributor: String?): JournalPushPickerAction {
    return if (success && savedDistributor != null) {
        JournalPushPickerAction.StorePick(savedDistributor)
    } else {
        JournalPushPickerAction.Enqueue
    }
}
