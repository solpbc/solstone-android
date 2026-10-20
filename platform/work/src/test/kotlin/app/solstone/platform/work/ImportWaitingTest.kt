// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import kotlin.test.Test
import kotlin.test.assertEquals

class ImportWaitingTest {
    private fun reason(
        paired: Boolean = true,
        incomplete: Boolean = false,
        awaiting: Boolean = false,
        refused: Boolean = false,
        gate: ImportVersionGate = ImportVersionGate.SUPPORTED,
        sending: Boolean = false,
    ) = importWaitingReason(paired, incomplete, awaiting, refused, gate, sending)

    @Test
    fun eachReasonWinsOverEverythingBelowIt() {
        // Unpaired outranks every other input, an unknown version included.
        assertEquals(ImportWaitingReason.UNPAIRED, reason(paired = false, incomplete = true, awaiting = true, refused = true, gate = ImportVersionGate.UNKNOWN, sending = true))
        assertEquals(ImportWaitingReason.AWAITING_CONFIRMATION, reason(awaiting = true, refused = true, gate = ImportVersionGate.TOO_OLD, sending = true))
        assertEquals(ImportWaitingReason.PAIRING_NEEDS_ATTENTION, reason(refused = true, gate = ImportVersionGate.UNKNOWN, sending = true))
        assertEquals(ImportWaitingReason.PAIRING_NEEDS_ATTENTION, reason(incomplete = true, gate = ImportVersionGate.TOO_OLD))
        assertEquals(ImportWaitingReason.CHECKING_JOURNAL, reason(gate = ImportVersionGate.UNKNOWN, sending = true))
        assertEquals(ImportWaitingReason.JOURNAL_TOO_OLD, reason(gate = ImportVersionGate.TOO_OLD, sending = true))
        assertEquals(ImportWaitingReason.SENDING, reason(sending = true))
        assertEquals(ImportWaitingReason.WAITING, reason())
    }

    @Test
    fun justPairedWithNoVersionRecordIsCheckingNotTooOld() {
        assertEquals(ImportWaitingReason.CHECKING_JOURNAL, reason(gate = importVersionGate(null, "inst", "ca")))
    }

    @Test
    fun theReasonAgreesWithTheDeliveryGate() {
        // Delivery sends exactly when the gate is SUPPORTED; the reason reports a version hold exactly then.
        ImportVersionGate.values().forEach { gate ->
            val held = reason(gate = gate) in setOf(ImportWaitingReason.CHECKING_JOURNAL, ImportWaitingReason.JOURNAL_TOO_OLD)
            assertEquals(gate != ImportVersionGate.SUPPORTED, held, "gate $gate")
        }
    }
}
