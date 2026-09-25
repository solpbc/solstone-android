// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class JournalPushDecisionsTest {

    @Test
    fun pathTable() {
        val longPath = "/app/" + "a".repeat(395)
        val accepted = listOf(
            "/app/health",
            "/app/journal/today",
            "/app/",
            longPath,
            "/app/network/api/clients",
            "/app/a..b",
            "/app/...",
        )
        for (path in accepted) {
            assertEquals(path, validJournalOpenPath(path), "Path should be accepted: $path")
        }

        val rejected = listOf(
            "/app/../network",
            "/app/./x",
            "/app/x/..",
            "/app//x",
            "/app/x?y=1",
            "/app/x#y",
            "/app/%2e%2e",
            "/app/x\n",
            "https://example.com/app/x",
            "/network/api/clients",
            "/app/ x",
            "/app/a\\b",
            "/app",
            null,
            "",
        )
        for (path in rejected) {
            assertNull(validJournalOpenPath(path), "Path should be rejected: $path")
        }
    }

    @Test
    fun openDecision() {
        val validPath = "/app/health"
        assertEquals(
            validPath,
            journalOpenPath(
                pushRegistration = true,
                freshCreate = true,
                launchedFromHistory = false,
                pairingCommitted = true,
                rawPath = validPath,
            ),
        )

        // Flipping any one gate yields null
        assertNull(
            journalOpenPath(
                pushRegistration = false,
                freshCreate = true,
                launchedFromHistory = false,
                pairingCommitted = true,
                rawPath = validPath,
            ),
        )
        assertNull(
            journalOpenPath(
                pushRegistration = true,
                freshCreate = false,
                launchedFromHistory = false,
                pairingCommitted = true,
                rawPath = validPath,
            ),
        )
        assertNull(
            journalOpenPath(
                pushRegistration = true,
                freshCreate = true,
                launchedFromHistory = true,
                pairingCommitted = true,
                rawPath = validPath,
            ),
        )
        assertNull(
            journalOpenPath(
                pushRegistration = true,
                freshCreate = true,
                launchedFromHistory = false,
                pairingCommitted = false,
                rawPath = validPath,
            ),
        )
        assertNull(
            journalOpenPath(
                pushRegistration = true,
                freshCreate = true,
                launchedFromHistory = false,
                pairingCommitted = true,
                rawPath = "/app/../invalid",
            ),
        )
    }

    @Test
    fun rowMapper() {
        val own = "app.solstone.phone"
        val ext = "io.heckel.ntfy"

        // Ready, allowed, channel not blocked -> On. Ready with no channel (journalChannelBlocked = false) -> On.
        assertEquals(
            JournalNotificationRow.On,
            journalNotificationRow(
                state = PushDeliveryState.Ready,
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = setOf(ext),
                ownPackage = own,
                appLabel = "Ntfy",
                stopped = false,
            ),
        )

        // Ready, channel blocked -> null
        assertNull(
            journalNotificationRow(
                state = PushDeliveryState.Ready,
                notificationsAllowed = true,
                journalChannelBlocked = true,
                distributorPackages = setOf(ext),
                ownPackage = own,
                appLabel = "Ntfy",
                stopped = false,
            ),
        )

        // Ready, notifications not allowed -> null
        assertNull(
            journalNotificationRow(
                state = PushDeliveryState.Ready,
                notificationsAllowed = false,
                journalChannelBlocked = false,
                distributorPackages = setOf(ext),
                ownPackage = own,
                appLabel = "Ntfy",
                stopped = false,
            ),
        )

        // Second call with the same Ready object and journalChannelBlocked = true -> null
        val readyState = PushDeliveryState.Ready
        assertNull(
            journalNotificationRow(
                state = readyState,
                notificationsAllowed = true,
                journalChannelBlocked = true,
                distributorPackages = setOf(ext),
                ownPackage = own,
                appLabel = "Ntfy",
                stopped = false,
            ),
        )

        // NoDeliveryApp: empty set -> NeedsDeliveryApp. Only own package -> NeedsDeliveryApp.
        assertEquals(
            JournalNotificationRow.NeedsDeliveryApp,
            journalNotificationRow(
                state = PushDeliveryState.NoDeliveryApp,
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = emptySet(),
                ownPackage = own,
                appLabel = null,
                stopped = null,
            ),
        )
        assertEquals(
            JournalNotificationRow.NeedsDeliveryApp,
            journalNotificationRow(
                state = PushDeliveryState.NoDeliveryApp,
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = setOf(own),
                ownPackage = own,
                appLabel = null,
                stopped = null,
            ),
        )

        // NoDeliveryApp: Exactly one external -> null. Two packages -> null. distributorPackages = null -> null.
        assertNull(
            journalNotificationRow(
                state = PushDeliveryState.NoDeliveryApp,
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = setOf(ext),
                ownPackage = own,
                appLabel = null,
                stopped = null,
            ),
        )
        assertNull(
            journalNotificationRow(
                state = PushDeliveryState.NoDeliveryApp,
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = setOf(own, ext),
                ownPackage = own,
                appLabel = null,
                stopped = null,
            ),
        )
        assertNull(
            journalNotificationRow(
                state = PushDeliveryState.NoDeliveryApp,
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = null,
                ownPackage = own,
                appLabel = null,
                stopped = null,
            ),
        )

        // ChooseDeliveryApp -> ChooseDeliveryApp
        assertEquals(
            JournalNotificationRow.ChooseDeliveryApp,
            journalNotificationRow(
                state = PushDeliveryState.ChooseDeliveryApp,
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = setOf(own, ext),
                ownPackage = own,
                appLabel = null,
                stopped = null,
            ),
        )

        // InsecureAddress -> InsecureAddress
        assertEquals(
            JournalNotificationRow.InsecureAddress,
            journalNotificationRow(
                state = PushDeliveryState.InsecureAddress,
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = setOf(ext),
                ownPackage = own,
                appLabel = null,
                stopped = null,
            ),
        )

        // WaitingForDelivery(external, true), stopped true, label "Ntfy" -> DeliveryAppStopped("Ntfy")
        assertEquals(
            JournalNotificationRow.DeliveryAppStopped("Ntfy"),
            journalNotificationRow(
                state = PushDeliveryState.WaitingForDelivery(ext, unanswered = true),
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = setOf(ext),
                ownPackage = own,
                appLabel = "Ntfy",
                stopped = true,
            ),
        )

        // WaitingForDelivery(external, true): stopped false -> null; stopped null -> null; label null -> null
        assertNull(
            journalNotificationRow(
                state = PushDeliveryState.WaitingForDelivery(ext, unanswered = true),
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = setOf(ext),
                ownPackage = own,
                appLabel = "Ntfy",
                stopped = false,
            ),
        )
        assertNull(
            journalNotificationRow(
                state = PushDeliveryState.WaitingForDelivery(ext, unanswered = true),
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = setOf(ext),
                ownPackage = own,
                appLabel = "Ntfy",
                stopped = null,
            ),
        )
        assertNull(
            journalNotificationRow(
                state = PushDeliveryState.WaitingForDelivery(ext, unanswered = true),
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = setOf(ext),
                ownPackage = own,
                appLabel = null,
                stopped = true,
            ),
        )

        // WaitingForDelivery(own, true), stopped true -> null
        assertNull(
            journalNotificationRow(
                state = PushDeliveryState.WaitingForDelivery(own, unanswered = true),
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = setOf(own),
                ownPackage = own,
                appLabel = "Solstone",
                stopped = true,
            ),
        )

        // WaitingForDelivery(external, false) -> null
        assertNull(
            journalNotificationRow(
                state = PushDeliveryState.WaitingForDelivery(ext, unanswered = false),
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = setOf(ext),
                ownPackage = own,
                appLabel = "Ntfy",
                stopped = true,
            ),
        )

        // JournalHasNoPush, NotLinked, Failed, Off -> null
        assertNull(
            journalNotificationRow(
                state = PushDeliveryState.JournalHasNoPush,
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = setOf(ext),
                ownPackage = own,
                appLabel = "Ntfy",
                stopped = false,
            ),
        )
        assertNull(
            journalNotificationRow(
                state = PushDeliveryState.NotLinked,
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = setOf(ext),
                ownPackage = own,
                appLabel = "Ntfy",
                stopped = false,
            ),
        )
        assertNull(
            journalNotificationRow(
                state = PushDeliveryState.Failed("x"),
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = setOf(ext),
                ownPackage = own,
                appLabel = "Ntfy",
                stopped = false,
            ),
        )
        assertNull(
            journalNotificationRow(
                state = PushDeliveryState.Off,
                notificationsAllowed = true,
                journalChannelBlocked = false,
                distributorPackages = setOf(ext),
                ownPackage = own,
                appLabel = "Ntfy",
                stopped = false,
            ),
        )
    }

    @Test
    fun repairDecision() {
        val own = "app.solstone.phone"
        val ext = "io.heckel.ntfy"
        var distributorsReadCount = 0
        var stoppedCallCount = 0
        var currentDistributors = setOf(ext)
        var stoppedReturn: Boolean? = false

        val readDistributors: () -> Set<String> = {
            distributorsReadCount++
            currentDistributors
        }
        val stopped: (String) -> Boolean? = {
            stoppedCallCount++
            stoppedReturn
        }

        var memory = JournalPushRepairMemory()

        // Two NoDeliveryApp resumes both Enqueue, including when set is unchanged
        val d1 = journalPushRepair(
            pushRegistration = true,
            pairingCommitted = true,
            state = PushDeliveryState.NoDeliveryApp,
            nowMillis = 1000L,
            memory = memory,
            ownPackage = own,
            readDistributors = readDistributors,
            stopped = stopped,
        )
        assertEquals(JournalPushRepairAction.Enqueue, d1.action)
        assertEquals(setOf(ext), d1.memory.distributorPackages)
        memory = d1.memory

        val d2 = journalPushRepair(
            pushRegistration = true,
            pairingCommitted = true,
            state = PushDeliveryState.NoDeliveryApp,
            nowMillis = 2000L,
            memory = memory,
            ownPackage = own,
            readDistributors = readDistributors,
            stopped = stopped,
        )
        assertEquals(JournalPushRepairAction.Enqueue, d2.action)
        memory = d2.memory

        // ChooseDeliveryApp -> Enqueue
        val d3 = journalPushRepair(
            pushRegistration = true,
            pairingCommitted = true,
            state = PushDeliveryState.ChooseDeliveryApp,
            nowMillis = 3000L,
            memory = memory,
            ownPackage = own,
            readDistributors = readDistributors,
            stopped = stopped,
        )
        assertEquals(JournalPushRepairAction.Enqueue, d3.action)

        // Ready first pass (memory set null) stores set, action None, does not enqueue
        val d4 = journalPushRepair(
            pushRegistration = true,
            pairingCommitted = true,
            state = PushDeliveryState.Ready,
            nowMillis = 4000L,
            memory = JournalPushRepairMemory(),
            ownPackage = own,
            readDistributors = readDistributors,
            stopped = stopped,
        )
        assertEquals(JournalPushRepairAction.None, d4.action)
        assertEquals(setOf(ext), d4.memory.distributorPackages)
        memory = d4.memory

        // Same set again -> None
        val d5 = journalPushRepair(
            pushRegistration = true,
            pairingCommitted = true,
            state = PushDeliveryState.Ready,
            nowMillis = 5000L,
            memory = memory,
            ownPackage = own,
            readDistributors = readDistributors,
            stopped = stopped,
        )
        assertEquals(JournalPushRepairAction.None, d5.action)

        // Different set -> Enqueue
        currentDistributors = setOf(ext, "another.pkg")
        val d6 = journalPushRepair(
            pushRegistration = true,
            pairingCommitted = true,
            state = PushDeliveryState.Ready,
            nowMillis = 6000L,
            memory = memory,
            ownPackage = own,
            readDistributors = readDistributors,
            stopped = stopped,
        )
        assertEquals(JournalPushRepairAction.Enqueue, d6.action)
        memory = d6.memory

        // WaitingForDelivery(external, true), stopped true -> None
        stoppedReturn = true
        val d7 = journalPushRepair(
            pushRegistration = true,
            pairingCommitted = true,
            state = PushDeliveryState.WaitingForDelivery(ext, unanswered = true),
            nowMillis = 7000L,
            memory = memory,
            ownPackage = own,
            readDistributors = readDistributors,
            stopped = stopped,
        )
        assertEquals(JournalPushRepairAction.None, d7.action)
        memory = d7.memory

        // stopped false -> Reregister once. Second call 1 ms later -> None (shared cap)
        stoppedReturn = false
        val d8 = journalPushRepair(
            pushRegistration = true,
            pairingCommitted = true,
            state = PushDeliveryState.WaitingForDelivery(ext, unanswered = true),
            nowMillis = 10_000L,
            memory = memory,
            ownPackage = own,
            readDistributors = readDistributors,
            stopped = stopped,
        )
        assertEquals(JournalPushRepairAction.Reregister, d8.action)
        assertEquals(10_000L, d8.memory.lastReregisterAtMillis)
        memory = d8.memory

        val d9 = journalPushRepair(
            pushRegistration = true,
            pairingCommitted = true,
            state = PushDeliveryState.WaitingForDelivery(ext, unanswered = true),
            nowMillis = 10_001L,
            memory = memory,
            ownPackage = own,
            readDistributors = readDistributors,
            stopped = stopped,
        )
        assertEquals(JournalPushRepairAction.None, d9.action)
        assertEquals(10_000L, d9.memory.lastReregisterAtMillis)

        // Five InsecureAddress calls at t = 0, 1000, 2000, 3000, 4000 from fresh memory -> exactly one Reregister. Call at 0 + 60000 -> Reregister again.
        var freshMemory = JournalPushRepairMemory()
        val insec0 = journalPushRepair(
            pushRegistration = true,
            pairingCommitted = true,
            state = PushDeliveryState.InsecureAddress,
            nowMillis = 0L,
            memory = freshMemory,
            ownPackage = own,
            readDistributors = readDistributors,
            stopped = stopped,
        )
        assertEquals(JournalPushRepairAction.Reregister, insec0.action)
        assertEquals(0L, insec0.memory.lastReregisterAtMillis)
        freshMemory = insec0.memory

        for (t in listOf(1_000L, 2_000L, 3_000L, 4_000L)) {
            val insec = journalPushRepair(
                pushRegistration = true,
                pairingCommitted = true,
                state = PushDeliveryState.InsecureAddress,
                nowMillis = t,
                memory = freshMemory,
                ownPackage = own,
                readDistributors = readDistributors,
                stopped = stopped,
            )
            assertEquals(JournalPushRepairAction.None, insec.action)
            assertEquals(0L, insec.memory.lastReregisterAtMillis)
            freshMemory = insec.memory
        }

        val insec60k = journalPushRepair(
            pushRegistration = true,
            pairingCommitted = true,
            state = PushDeliveryState.InsecureAddress,
            nowMillis = 60_000L,
            memory = freshMemory,
            ownPackage = own,
            readDistributors = readDistributors,
            stopped = stopped,
        )
        assertEquals(JournalPushRepairAction.Reregister, insec60k.action)
        assertEquals(60_000L, insec60k.memory.lastReregisterAtMillis)

        // WaitingForDelivery(external, false), WaitingForDelivery(own, true), Off, Failed("x"), JournalHasNoPush, NotLinked -> None.
        // WaitingForDelivery(own, true) does not call stopped.
        stoppedCallCount = 0
        val nothingStates = listOf(
            PushDeliveryState.WaitingForDelivery(ext, unanswered = false),
            PushDeliveryState.WaitingForDelivery(own, unanswered = true),
            PushDeliveryState.Off,
            PushDeliveryState.Failed("x"),
            PushDeliveryState.JournalHasNoPush,
            PushDeliveryState.NotLinked,
        )
        for (st in nothingStates) {
            val res = journalPushRepair(
                pushRegistration = true,
                pairingCommitted = true,
                state = st,
                nowMillis = 100_000L,
                memory = freshMemory,
                ownPackage = own,
                readDistributors = readDistributors,
                stopped = stopped,
            )
            assertEquals(JournalPushRepairAction.None, res.action)
        }
        assertEquals(0, stoppedCallCount, "WaitingForDelivery(own, true) should not call stopped")

        // pairingCommitted = false with NoDeliveryApp -> None, neither lambda is invoked
        val throwingDist: () -> Set<String> = { error("should not be called") }
        val throwingStopped: (String) -> Boolean? = { error("should not be called") }
        val uncommittedRes = journalPushRepair(
            pushRegistration = true,
            pairingCommitted = false,
            state = PushDeliveryState.NoDeliveryApp,
            nowMillis = 1000L,
            memory = freshMemory,
            ownPackage = own,
            readDistributors = throwingDist,
            stopped = throwingStopped,
        )
        assertEquals(JournalPushRepairAction.None, uncommittedRes.action)
        assertEquals(freshMemory, uncommittedRes.memory)

        // pushRegistration = false the same
        val disabledRes = journalPushRepair(
            pushRegistration = false,
            pairingCommitted = true,
            state = PushDeliveryState.NoDeliveryApp,
            nowMillis = 1000L,
            memory = freshMemory,
            ownPackage = own,
            readDistributors = throwingDist,
            stopped = throwingStopped,
        )
        assertEquals(JournalPushRepairAction.None, disabledRes.action)
        assertEquals(freshMemory, disabledRes.memory)
    }

    @Test
    fun pickerResult() {
        val r1 = journalPushPickerResult(true, "io.heckel.ntfy")
        assertIs<JournalPushPickerAction.StorePick>(r1)
        assertEquals("io.heckel.ntfy", r1.packageName)

        val r2 = journalPushPickerResult(false, "io.heckel.ntfy")
        assertEquals(JournalPushPickerAction.Enqueue, r2)

        val r3 = journalPushPickerResult(true, null)
        assertEquals(JournalPushPickerAction.Enqueue, r3)

        val r4 = journalPushPickerResult(false, null)
        assertEquals(JournalPushPickerAction.Enqueue, r4)
    }
}
