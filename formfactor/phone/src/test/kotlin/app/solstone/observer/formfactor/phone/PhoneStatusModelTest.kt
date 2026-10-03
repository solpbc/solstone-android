// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PhoneStatusModelTest {
    @Test
    fun typedRecoveryEvidenceBlocksCleanStateWithoutChangingBacklogOrConnectivity() {
        val clean = PhoneStatusModel(true, true, 0, false, false, true, false, false)
        for (history in listOf(clean.copy(unresolvedOtherInterruption = true),
                clean.copy(unresolvedUnknownRecovery = true))) {
            assertEquals(StatusPillKind.SYNCING, statusPillKind(history))
            assertEquals(0, history.pendingCount)
            assertFalse(audioCustodyLines(history).contains(STATUS_AUDIO_INTERRUPTED_LEAD))
            assertEquals(StatusPillKind.OFFLINE, statusPillKind(history.copy(online = false)))
            assertEquals(StatusPillKind.SYNCING, statusPillKind(history.copy(pendingCount = 2)))
            assertFalse(audioCustodyLines(history.copy(pendingCount = 2)).isEmpty())
        }
        val both = clean.copy(unresolvedOtherInterruption = true, unresolvedAudioInterruption = true,
            unresolvedUnknownRecovery = true)
        assertEquals(STATUS_OTHER_INTERRUPTED_LEAD, statusPillText(both))
        assertEquals(3, audioCustodyLines(both).count { it in setOf(STATUS_AUDIO_INTERRUPTED_LEAD,
            STATUS_OTHER_INTERRUPTED_LEAD, STATUS_UNKNOWN_RECOVERY_LEAD) })
        assertEquals(StatusPillKind.CONNECTED, statusPillKind(clean))
    }

    @Test
    fun failedCheckNamesAnAddressOnlyWhenOneWasDialed() {
        assertEquals("couldn't reach your journal at 192.0.2.1:7657", checkConnectionUnreached("192.0.2.1:7657"))
        // A check that never dialed (a missing credential, say) keeps the plain words.
        assertEquals("couldn't reach your journal", checkConnectionUnreached(null))
        assertEquals("couldn't reach your journal", checkConnectionUnreached(""))
    }

    @Test
    fun fourPillStatesRenderQuotedCopy() {
        assertEquals(
            "connected",
            statusPillText(PhoneStatusModel(paired = true, online = true, pendingCount = 0, hasContentPending = false, awaitingMarkConfirmation = false, recoveryCompleted = true, audioAwaitingCustody = false, unresolvedAudioInterruption = false)),
        )
        assertEquals(
            "3 syncing",
            statusPillText(PhoneStatusModel(paired = true, online = true, pendingCount = 3, hasContentPending = true, awaitingMarkConfirmation = false, recoveryCompleted = true, audioAwaitingCustody = false, unresolvedAudioInterruption = false)),
        )
        assertEquals(
            "offline · 2 waiting",
            statusPillText(PhoneStatusModel(paired = true, online = false, pendingCount = 2, hasContentPending = true, awaitingMarkConfirmation = false, recoveryCompleted = true, audioAwaitingCustody = false, unresolvedAudioInterruption = false)),
        )
        assertEquals(
            "not paired",
            statusPillText(PhoneStatusModel(paired = false, online = true, pendingCount = 4, hasContentPending = true, awaitingMarkConfirmation = false, recoveryCompleted = true, audioAwaitingCustody = false, unresolvedAudioInterruption = false)),
        )
    }

    @Test
    fun awaitingMarkConfirmationPrecedence() {
        val beatsConnected = PhoneStatusModel(
            paired = true,
            online = true,
            pendingCount = 0,
            hasContentPending = false,
            awaitingMarkConfirmation = true,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
            unresolvedAudioInterruption = false,
        )
        assertEquals(StatusPillKind.AWAITING_MARK_CONFIRMATION, statusPillKind(beatsConnected))

        val beatsSyncing = PhoneStatusModel(
            paired = true,
            online = true,
            pendingCount = 5,
            hasContentPending = true,
            awaitingMarkConfirmation = true,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
            unresolvedAudioInterruption = false,
        )
        assertEquals(StatusPillKind.AWAITING_MARK_CONFIRMATION, statusPillKind(beatsSyncing))

        val offlineBeatsAwaiting = PhoneStatusModel(
            paired = true,
            online = false,
            pendingCount = 2,
            hasContentPending = true,
            awaitingMarkConfirmation = true,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
            unresolvedAudioInterruption = false,
        )
        assertEquals(StatusPillKind.OFFLINE, statusPillKind(offlineBeatsAwaiting))
        assertEquals("offline · 2 waiting", statusPillText(offlineBeatsAwaiting))

        val notPairedModel = PhoneStatusModel(
            paired = false,
            online = true,
            pendingCount = 0,
            hasContentPending = false,
            awaitingMarkConfirmation = true,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
            unresolvedAudioInterruption = false,
        )
        assertEquals(StatusPillKind.NOT_PAIRED, statusPillKind(notPairedModel))
        assertEquals("not paired", statusPillText(notPairedModel))
    }

    @Test
    fun pendingFlagIsNotSummedIntoCount() {
        val model = PhoneStatusModel(
            paired = true,
            online = true,
            pendingCount = 3,
            hasContentPending = true,
            awaitingMarkConfirmation = false,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
            unresolvedAudioInterruption = false,
        )
        assertEquals("3 syncing", statusPillText(model))
        assertFalse(statusPillText(model).contains("4"))
    }

    @Test
    fun retiredOfflineFormDoesNotAppear() {
        val text = statusPillText(
            PhoneStatusModel(paired = true, online = false, pendingCount = 38, hasContentPending = true, awaitingMarkConfirmation = false, recoveryCompleted = true, audioAwaitingCustody = false, unresolvedAudioInterruption = false),
        )
        assertEquals("offline · 38 waiting", text)
        assertFalse(text.contains("38 offline"))
    }

    @Test
    fun audioCustodyAndInterruptionPillAndLines() {
        val interrupted = PhoneStatusModel(
            paired = true,
            online = true,
            pendingCount = 0,
            hasContentPending = false,
            awaitingMarkConfirmation = false,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
            unresolvedAudioInterruption = true,
        )
        assertEquals(StatusPillKind.SYNCING, statusPillKind(interrupted))
        assertEquals("audio was interrupted", statusPillText(interrupted))
        assertEquals(
            listOf("audio was interrupted", "some audio hasn't reached your journal."),
            audioCustodyLines(interrupted),
        )

        val custody = PhoneStatusModel(
            paired = true,
            online = true,
            pendingCount = 0,
            hasContentPending = false,
            awaitingMarkConfirmation = false,
            recoveryCompleted = true,
            audioAwaitingCustody = true,
            unresolvedAudioInterruption = false,
        )
        assertEquals(StatusPillKind.SYNCING, statusPillKind(custody))
        assertEquals("waiting to sync", statusPillText(custody))
        assertEquals(
            listOf("waiting to sync", "on this device"),
            audioCustodyLines(custody),
        )

        val both = PhoneStatusModel(
            paired = true,
            online = true,
            pendingCount = 0,
            hasContentPending = false,
            awaitingMarkConfirmation = false,
            recoveryCompleted = true,
            audioAwaitingCustody = true,
            unresolvedAudioInterruption = true,
        )
        assertEquals(StatusPillKind.SYNCING, statusPillKind(both))
        assertEquals("audio was interrupted", statusPillText(both))
        assertEquals(
            listOf(
                "audio was interrupted",
                "some audio hasn't reached your journal.",
                "waiting to sync",
                "on this device",
            ),
            audioCustodyLines(both),
        )

        val recovering = PhoneStatusModel(
            paired = true,
            online = true,
            pendingCount = 0,
            hasContentPending = false,
            awaitingMarkConfirmation = false,
            recoveryCompleted = false,
            audioAwaitingCustody = false,
            unresolvedAudioInterruption = false,
        )
        assertEquals(StatusPillKind.SYNCING, statusPillKind(recovering))
        assertEquals("0 syncing", statusPillText(recovering))
        assertEquals(
            emptyList<String>(),
            audioCustodyLines(recovering),
        )
    }

    @Test
    fun journalVersionDisplayTextFormatsExpectedCopy() {
        assertEquals("unknown", journalVersionDisplayText(null))
        assertEquals(
            "unknown",
            journalVersionDisplayText(
                app.solstone.core.pl.JournalVersionReading(
                    version = "0.9.1",
                    freshness = app.solstone.core.pl.JournalVersionFreshness.NEVER_OBSERVED,
                ),
            ),
        )
        assertEquals(
            "0.9.1 (last known)",
            journalVersionDisplayText(
                app.solstone.core.pl.JournalVersionReading(
                    version = "0.9.1",
                    freshness = app.solstone.core.pl.JournalVersionFreshness.LAST_KNOWN,
                ),
            ),
        )
        assertEquals(
            "0.9.1",
            journalVersionDisplayText(
                app.solstone.core.pl.JournalVersionReading(
                    version = "0.9.1",
                    freshness = app.solstone.core.pl.JournalVersionFreshness.CURRENT,
                ),
            ),
        )
    }
}
