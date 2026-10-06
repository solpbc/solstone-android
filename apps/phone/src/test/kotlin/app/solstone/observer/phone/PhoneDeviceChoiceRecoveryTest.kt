// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.pl.PairingMigrationStage
import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneDeviceChoiceRecoveryTest {
    @Test
    fun persistedSelectionRefreshesListBeforeConfirmation() {
        assertEquals(
            PhoneDeviceChoiceRecoveryAction.REFRESH_CLIENTS,
            phoneDeviceChoiceRecoveryAction(PairingMigrationStage.AWAITING_SELECTION),
        )
        assertEquals(
            PhoneDeviceChoiceRecoveryAction.RESUME_DECISION,
            phoneDeviceChoiceRecoveryAction(PairingMigrationStage.SUBMITTED_UNKNOWN),
        )
        assertEquals(
            PhoneDeviceChoiceRecoveryAction.SHOW_CURRENT,
            phoneDeviceChoiceRecoveryAction(PairingMigrationStage.SHOWN_DEFERRED),
        )
    }
}
