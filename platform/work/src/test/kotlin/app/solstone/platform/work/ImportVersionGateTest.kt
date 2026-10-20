// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.JournalVersionRecord
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
