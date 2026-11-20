// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.JournalVersionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportVersionGateTest {
    private fun record(version: String, instance: String = "inst", ca: String = "ca") =
        JournalVersionRecord(instanceId = instance, caChainFingerprint = ca, version = version)

    private fun gate(record: JournalVersionRecord?) = importVersionGate(record, "inst", "ca")

    @Test
    fun theFirstSupportingReleaseAndNewerOnesAreSupported() {
        assertEquals(ImportVersionGate.SUPPORTED, gate(record("2.0.52")))
        assertEquals(ImportVersionGate.SUPPORTED, gate(record("2.0.100")))
        assertEquals(ImportVersionGate.SUPPORTED, gate(record("2.1.0")))
        assertEquals(ImportVersionGate.SUPPORTED, gate(record("v2.0.52")))
        assertEquals(ImportVersionGate.SUPPORTED, gate(record("2.0.52-dev+abc")))
    }

    @Test
    fun olderReleasesAreTooOld() {
        assertEquals(ImportVersionGate.TOO_OLD, gate(record("2.0.51")))
        assertEquals(ImportVersionGate.TOO_OLD, gate(record("1.0.22")))
    }

    @Test
    fun anUnreadableVersionIsTooOldNotStillChecking() {
        assertEquals(ImportVersionGate.TOO_OLD, gate(record("")))
        assertEquals(ImportVersionGate.TOO_OLD, gate(record("2.0")))
        assertEquals(ImportVersionGate.TOO_OLD, gate(record("unknown")))
    }

    @Test
    fun noRecordOrAnotherJournalsRecordIsStillChecking() {
        assertEquals(ImportVersionGate.UNKNOWN, gate(null))
        assertEquals(ImportVersionGate.UNKNOWN, gate(record("2.0.60", instance = "other")))
        assertEquals(ImportVersionGate.UNKNOWN, gate(record("2.0.60", ca = "other-ca")))
    }

    @Test
    fun photoCaptionsRequireJournal2086OrNewer() {
        assertTrue(journalSupportsImportCaptions("2.0.86"))
        assertTrue(journalSupportsImportCaptions("2.0.100"))
        assertTrue(journalSupportsImportCaptions("2.1.0"))
        assertTrue(journalSupportsImportCaptions("v2.0.86"))
        assertTrue(journalSupportsImportCaptions("2.0.86-dev+abc"))
        assertFalse(journalSupportsImportCaptions("2.0.85"))
        assertFalse(journalSupportsImportCaptions("2.0.52"))
        assertFalse(journalSupportsImportCaptions(""))
        assertFalse(journalSupportsImportCaptions("unknown"))
    }

    @Test
    fun effectiveGateReportsTooOldOnlyWhenEveryWaitingItemNeedsCaptionSupport() {
        assertEquals(
            ImportVersionGate.TOO_OLD,
            effectiveImportGate(ImportVersionGate.SUPPORTED, "2.0.85", totalWaiting = 1, heldForCaption = 1),
        )
        assertEquals(
            ImportVersionGate.SUPPORTED,
            effectiveImportGate(ImportVersionGate.SUPPORTED, "2.0.85", totalWaiting = 2, heldForCaption = 1),
        )
        assertEquals(
            ImportVersionGate.SUPPORTED,
            effectiveImportGate(ImportVersionGate.SUPPORTED, "2.0.86", totalWaiting = 1, heldForCaption = 0),
        )
        assertEquals(
            ImportVersionGate.SUPPORTED,
            effectiveImportGate(ImportVersionGate.SUPPORTED, "2.0.85", totalWaiting = 0, heldForCaption = 0),
        )
        assertEquals(
            ImportVersionGate.UNKNOWN,
            effectiveImportGate(ImportVersionGate.UNKNOWN, "2.0.85", totalWaiting = 1, heldForCaption = 1),
        )
    }
}
