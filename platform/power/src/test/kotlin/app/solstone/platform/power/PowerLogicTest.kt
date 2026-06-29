// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.power

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerLogicTest {
    @Test
    fun guidanceSelectsRogbidByModel() {
        val selected = OemGuidanceCatalog.select(DeviceFingerprint("unknown", "wearable", "Rogbid Model X"))

        assertEquals("rogbid", selected.id)
    }

    @Test
    fun guidanceSelectsRokidByBrand() {
        val selected = OemGuidanceCatalog.select(DeviceFingerprint("unknown", "Rokid", "unknown"))

        assertEquals("rokid", selected.id)
    }

    @Test
    fun guidanceSelectsRokidByRv203Model() {
        val selected = OemGuidanceCatalog.select(DeviceFingerprint("unknown", "unknown", "RV203 glasses"))

        assertEquals("rokid", selected.id)
    }

    @Test
    fun guidanceSelectsSamsungByManufacturer() {
        val selected = OemGuidanceCatalog.select(DeviceFingerprint("Samsung", "galaxy", "phone"))

        assertEquals("samsung", selected.id)
    }

    @Test
    fun guidanceFallsBackToGeneric() {
        val selected = OemGuidanceCatalog.select(DeviceFingerprint("Example", "Example", "Example"))

        assertEquals("generic", selected.id)
    }

    @Test
    fun everyGuidanceEntryHasBatteryAndAutostartActions() {
        listOf(OemGuidanceCatalog.generic, OemGuidanceCatalog.rokid, OemGuidanceCatalog.rogbid, OemGuidanceCatalog.samsung).forEach { entry ->
            assertTrue(entry.batteryExemption.instructionText.isNotBlank())
            assertTrue(entry.autostart.instructionText.isNotBlank())
            assertTrue(entry.batteryExemption.intentAction.isNotBlank())
            assertTrue(entry.autostart.intentAction.isNotBlank())
        }
    }

    @Test
    fun onlyRokidMarksAutostartUnavailable() {
        assertTrue(OemGuidanceCatalog.generic.autostartAvailable)
        assertTrue(OemGuidanceCatalog.rogbid.autostartAvailable)
        assertTrue(OemGuidanceCatalog.samsung.autostartAvailable)
        assertFalse(OemGuidanceCatalog.rokid.autostartAvailable)
    }

    @Test
    fun exemptionRequiresBatteryExemptionAndAutostartConfirmation() {
        assertFalse(verifier(battery = false, autostart = false).isExemptionVerified())
        assertFalse(verifier(battery = true, autostart = false).isExemptionVerified())
        assertFalse(verifier(battery = false, autostart = true).isExemptionVerified())
        assertTrue(verifier(battery = true, autostart = true).isExemptionVerified())
    }

    @Test
    fun exemptionCanUseBatteryOnlyWhenAutostartIsUnavailable() {
        assertFalse(verifier(battery = false, autostart = false, autostartRequired = false).isExemptionVerified())
        assertFalse(verifier(battery = false, autostart = true, autostartRequired = false).isExemptionVerified())
        assertTrue(verifier(battery = true, autostart = false, autostartRequired = false).isExemptionVerified())
        assertTrue(verifier(battery = true, autostart = true, autostartRequired = false).isExemptionVerified())
    }

    @Test
    fun storageStatusUsesThreshold() {
        assertFalse(StorageStatus(UsableSpaceProvider { 99L }, minimumFreeBytes = 100L).isStorageOk())
        assertTrue(StorageStatus(UsableSpaceProvider { 100L }, minimumFreeBytes = 100L).isStorageOk())
    }

    private fun verifier(battery: Boolean, autostart: Boolean, autostartRequired: Boolean = true): ExemptionVerifier =
        ExemptionVerifier(
            batteryExemptionStatus = BatteryExemptionStatus { battery },
            autostartConfirmationStore = FakeAutostartConfirmationStore(autostart),
            autostartRequired = autostartRequired,
        )

    private class FakeAutostartConfirmationStore(initial: Boolean) : AutostartConfirmationStore {
        private var confirmed = initial

        override fun isAutostartConfirmed(): Boolean = confirmed

        override fun setAutostartConfirmed(confirmed: Boolean) {
            this.confirmed = confirmed
        }
    }
}
