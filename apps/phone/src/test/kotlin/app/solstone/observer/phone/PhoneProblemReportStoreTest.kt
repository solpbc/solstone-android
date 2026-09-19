// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneProblemReportStoreTest {
    @Test
    fun savesLocallyAndKeepsOnlyTenNewestReports() {
        val directory = Files.createTempDirectory("phone-problem-reports").toFile()
        val store = PhoneProblemReportStore(directory)

        repeat(12) { index ->
            store.save("report-$index", Instant.parse("2026-09-19T00:00:${index.toString().padStart(2, '0')}Z"))
        }

        val reports = store.list()
        assertEquals(10, reports.size)
        assertTrue(reports.first().savedAt.contains("11Z"))
        assertTrue(reports.none { it.savedAt.contains("00Z") || it.savedAt.contains("01Z") })
    }
}
