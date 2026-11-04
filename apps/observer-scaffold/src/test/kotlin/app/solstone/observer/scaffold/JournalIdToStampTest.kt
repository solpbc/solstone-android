// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import app.solstone.platform.identity.file.CommitMarkerStatus
import app.solstone.platform.identity.file.PairingCommitMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JournalIdToStampTest {
    private val committed = PairingCommitMarker(
        status = CommitMarkerStatus.COMMITTED,
        instanceId = "journal-b",
        clientCertSha256 = "sha256:b",
    )

    // Falsified by stamping on install: pairing marks every gap notice seen, and a no couldn't undo it.
    @Test
    fun aPairingTheOwnerHasNotAnsweredForStampsNothing() {
        assertNull(journalIdToStamp(committed) { false })
    }

    @Test
    fun aPairingTheOwnerAnsweredForStampsItsJournal() {
        assertEquals("journal-b", journalIdToStamp(committed) { it == "sha256:b" })
    }

    @Test
    fun noCommittedPairingStampsNothing() {
        assertNull(journalIdToStamp(null) { true })
        assertNull(journalIdToStamp(PairingCommitMarker.absent()) { true })
    }
}
