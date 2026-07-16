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
    fun samsungAndGenericExposeTheirSelectedBatteryActions() {
        val samsung = OemGuidanceCatalog.select(DeviceFingerprint("Samsung", "galaxy", "phone"))
        val generic = OemGuidanceCatalog.select(DeviceFingerprint("Example", "Example", "Example"))

        assertEquals(OemGuidanceCatalog.samsung.batteryExemption, samsung.batteryExemption)
        assertEquals(OemGuidanceCatalog.generic.batteryExemption, generic.batteryExemption)
    }

    @Test
    fun detailsTargetInjectsPackageName() {
        val prepared = OemGuidanceCatalog.samsung.batteryExemption.prepareIntent("app.solstone.phone")

        assertEquals(
            GuidanceIntentPreparation.Ready(
                action = "android.settings.APPLICATION_DETAILS_SETTINGS",
                data = "package:app.solstone.phone",
            ),
            prepared,
        )
    }

    @Test
    fun blankPackageIsRejectedBeforeResolution() {
        assertEquals(
            GuidanceIntentPreparation.Invalid("app package is unavailable"),
            OemGuidanceCatalog.samsung.batteryExemption.prepareIntent(" "),
        )
        assertEquals(
            GuidanceIntentPreparation.Invalid("app package is unavailable"),
            OemGuidanceCatalog.generic.batteryExemption.prepareIntent(""),
        )
    }

    @Test
    fun detailsActionWithoutSemanticPackageTargetIsRejected() {
        val action = GuidanceAction(
            intentAction = "android.settings.APPLICATION_DETAILS_SETTINGS",
            intentData = null,
            instructionText = "Open app settings.",
        )

        assertEquals(
            GuidanceIntentPreparation.Invalid("app settings target is missing"),
            action.prepareIntent("app.solstone.phone"),
        )
    }

    @Test
    fun exemptionReflectsBatteryExemption() {
        assertFalse(verifier(battery = false).isExemptionVerified())
        assertTrue(verifier(battery = true).isExemptionVerified())
    }

    @Test
    fun storageStatusUsesThreshold() {
        assertFalse(StorageStatus(UsableSpaceProvider { 99L }, minimumFreeBytes = 100L).isStorageOk())
        assertTrue(StorageStatus(UsableSpaceProvider { 100L }, minimumFreeBytes = 100L).isStorageOk())
    }

    private fun verifier(battery: Boolean): ExemptionVerifier =
        ExemptionVerifier(batteryExemptionStatus = BatteryExemptionStatus { battery })
}
