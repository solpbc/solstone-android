// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ResumableImportUiTest {
    @Test
    fun tileSummaryShowsConfirmedProgressCheckingPausedAndQueuedCounts() {
        val sending = PhoneImportRow(
            id = "big-1",
            name = "export.zip",
            kind = ImportRowKind.SENDING,
            bytesSent = 1_200_000_000L,
            bytesTotal = 4_000_000_000L,
            resumable = true,
            uploadPhase = "sending",
        )
        val singleSummary = importTileActiveSummary(PhoneImportPaneState(rows = listOf(sending)))
        assertNotNull(singleSummary)
        assertEquals(ImportMarkerKind.SENDING, singleSummary.marker)
        assertEquals("sending", singleSummary.stateWord)
        assertEquals("1.2 GB of 4.0 GB", singleSummary.subLine)

        val queuedBehind = listOf(
            sending,
            PhoneImportRow("w1", "a.pdf", ImportRowKind.WAITING),
            PhoneImportRow("w2", "b.pdf", ImportRowKind.WAITING),
        )
        val queuedSummary = importTileActiveSummary(PhoneImportPaneState(rows = queuedBehind))!!
        assertEquals("sending", queuedSummary.stateWord)
        assertEquals("1.2 GB of 4.0 GB · 2 more waiting", queuedSummary.subLine)

        val checkingSummary = importTileActiveSummary(
            PhoneImportPaneState(rows = listOf(sending.copy(bytesSent = 4_000_000_000L, uploadPhase = "checking"))),
        )!!
        assertEquals("checking file", checkingSummary.stateWord)
        assertEquals("export.zip", checkingSummary.subLine)

        val pausedSummary = importTileActiveSummary(
            PhoneImportPaneState(rows = listOf(sending.copy(kind = ImportRowKind.WAITING, uploadPhase = "paused"))),
        )!!
        assertEquals(ImportMarkerKind.WAITING, pausedSummary.marker)
        assertEquals("paused", pausedSummary.stateWord)
        assertEquals("1.2 GB of 4.0 GB", pausedSummary.subLine)

        // Needs attention still beats sending/paused under worst-signal-wins.
        val faultSummary = importTileActiveSummary(
            PhoneImportPaneState(
                rows = listOf(
                    sending,
                    PhoneImportRow("f1", "full.zip", ImportRowKind.REFUSED, reasonCode = "insufficient_journal_space", bytesTotal = 4_000_000_000L),
                ),
            ),
        )!!
        assertEquals(ImportMarkerKind.NEEDS_ATTENTION, faultSummary.marker)
        assertEquals("needs attention", faultSummary.stateWord)
        assertEquals("full.zip", faultSummary.subLine)
    }

    @Test
    fun rowDetailTextCoversProgressResumingCheckingPausedAndAllRealFaults() {
        val sending = PhoneImportRow(
            id = "r1",
            name = "archive.zip",
            kind = ImportRowKind.SENDING,
            bytesSent = 1_200_000_000L,
            bytesTotal = 4_000_000_000L,
            resumable = true,
            uploadPhase = "sending",
        )
        assertEquals("1.2 GB of 4.0 GB (30%)", importRowDetailText(sending))
        assertEquals("resuming from 1.2 GB of 4.0 GB", importRowDetailText(sending.copy(resuming = true)))
        assertEquals("checking the complete file", importRowDetailText(sending.copy(uploadPhase = "checking")))
        assertEquals(
            "paused at 1.2 GB of 4.0 GB. picks up from here.",
            importRowDetailText(sending.copy(kind = ImportRowKind.WAITING, uploadPhase = "paused")),
        )
        assertEquals(
            "not enough space where your journal lives for this 4.0 GB file. free up space there and try again.",
            importRowDetailText(
                PhoneImportRow(
                    id = "f-space",
                    name = "archive.zip",
                    kind = ImportRowKind.REFUSED,
                    reasonCode = "insufficient_journal_space",
                    bytesTotal = 4_000_000_000L,
                ),
            ),
        )
        assertEquals(
            "the file didn't arrive intact. try sending it again.",
            importRowDetailText(
                PhoneImportRow(
                    id = "f-hash",
                    name = "archive.zip",
                    kind = ImportRowKind.REFUSED,
                    reasonCode = "whole_file_checksum_mismatch",
                ),
            ),
        )
        assertEquals(
            "this unfinished import expired after waiting too long. try again to start over.",
            importRowDetailText(
                PhoneImportRow(
                    id = "f-exp",
                    name = "archive.zip",
                    kind = ImportRowKind.REFUSED,
                    reasonCode = "upload_not_found",
                ),
            ),
        )
        assertEquals(
            "this file could no longer be read on this device. remove it and choose the file again.",
            importRowDetailText(
                PhoneImportRow(
                    id = "f-missing",
                    name = "archive.zip",
                    kind = ImportRowKind.MISSING_COPY,
                ),
            ),
        )
    }
}
