// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.screenshots

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenshotBookTest {
    @Test
    fun aBookRoundTripsIncludingAPausedWindowAndARefusedHash() {
        val book = ScreenshotBook(
            treeUri = "content://folder/Pictures/Screenshots",
            folderName = "Screenshots",
            history = HistoryChoice.NOTHING_BEFORE_TODAY,
            confirmed = true,
            reconfirm = false,
            period = 2,
            lowerBoundMs = 1_700_000_000_000L,
            frozen = setOf("doc/1"),
            pauses = listOf(PauseSpan(10L, 20L), PauseSpan(30L, null)),
            received = listOf(ReceivedHash("abc", ReceivedEnd.REFUSED_TYPE)),
            staged = listOf(StagedShot("doc/1", "abc", "2026-11-03T14:32:07-07:00", "platform", "20261103/screenshots/leaf", 99L)),
            marks = listOf(DocMark("doc/1", ReceivedEnd.DELIVERED)),
            sourcePaused = true,
        )
        assertEquals(book, decodeBook(encodeBook(book)))
    }

    @Test
    fun aCheckTimeAloneLeavesTheBookTextAsItWasAndAnOlderBookStillReadsIt() {
        val book = ScreenshotBook(treeUri = "content://folder/Pictures/Screenshots", confirmed = true)
        // A check that finds nothing new changes only the check time, which has its own record.
        assertEquals(encodeBook(book), encodeBook(book.copy(lastCheckedMs = 50L)))
        assertEquals(50L, decodeBook(encodeBook(book) + "checked\t50\n").lastCheckedMs)
    }

    @Test
    fun nothingFromBeforeTodayStartsFromTodayEvenWhenSomeAreWaiting() {
        assertEquals("start from today", confirmLabel(HistoryChoice.NOTHING_BEFORE_TODAY, 4))
        assertEquals("bring in 4 screenshots", confirmLabel(HistoryChoice.LAST_30_DAYS, 4))
        assertEquals("start from today", confirmLabel(HistoryChoice.ALL, 0))
    }

    @Test
    fun fateSkipsWhatIsNotAScreenshotAndHoldsASendUntilConfirm() {
        val shot = ShotRead.Shot(ShotKind.PNG, "2026:11:03 14:32:07", 10, 10)
        assertEquals(ShotFate.SKIP, shotFate(ShotRead.NotScreenshot, 1_000L, null, emptyList(), 0L, emptyList(), true))
        assertEquals(ShotFate.COUNT, shotFate(shot, 1_000L, "abc", emptyList(), 0L, emptyList(), false))
        assertEquals(ShotFate.SEND, shotFate(shot, 1_000L, "abc", emptyList(), 0L, emptyList(), true))
        assertEquals(ShotFate.SKIP, shotFate(shot, 1_000L, "abc", emptyList(), 5_000L, emptyList(), true))
        assertEquals(
            ShotFate.ALREADY,
            shotFate(shot, 1_000L, "abc", listOf(ReceivedHash("abc", ReceivedEnd.DELIVERED)), 0L, emptyList(), true),
        )
        assertEquals(
            ShotFate.UNREADABLE,
            shotFate(ShotRead.Ended(ShotKind.PNG, ShotEnd.UNREADABLE), 1_000L, null, emptyList(), 0L, emptyList(), false),
        )
        assertEquals(
            ShotFate.REFUSED_SIZE,
            shotFate(ShotRead.Ended(ShotKind.JPEG, ShotEnd.REFUSED_SIZE), 1_000L, null, emptyList(), 0L, emptyList(), true),
        )
    }

    @Test
    fun theBackgroundCheckIsWantedOnlyWhileTheSourceIsOn() {
        val granted = ScreenshotBook(treeUri = "content://folder/Pictures/Screenshots")
        kotlin.test.assertFalse(backgroundCheckWanted(ScreenshotBook(), wishOn = true))
        kotlin.test.assertFalse(backgroundCheckWanted(granted, wishOn = true), "granted but how far back not chosen")
        kotlin.test.assertTrue(backgroundCheckWanted(granted.copy(confirmed = true), wishOn = true))
        kotlin.test.assertFalse(backgroundCheckWanted(granted.copy(confirmed = true), wishOn = false), "turned off")
    }

    @Test
    fun thePickerOpensWhereThisPhoneSavesScreenshots() {
        assertEquals("DCIM/Screenshots", screenshotsPickerFolder { if (it == "DCIM/Screenshots") 5L else null })
        assertEquals("Pictures/Screenshots", screenshotsPickerFolder { if (it == "Pictures/Screenshots") 5L else null })
        assertEquals("Pictures/Screenshots", screenshotsPickerFolder { null })
        assertEquals("DCIM/Screenshots", screenshotsPickerFolder { if (it == "DCIM/Screenshots") 9L else 2L })
    }

    @Test
    fun aNewPairingAsksHowFarBackAgainAndKeepsTheFolderThePauseAndWhatIsWaiting() {
        val held = StagedShot("doc/2", "def", "2026-11-03T14:32:07-07:00", "platform", "20261103/screenshots/leaf2", 99L)
        val pauses = listOf(PauseSpan(10L, null))
        val on = ScreenshotBook(
            treeUri = "content://folder",
            folderName = "Screenshots",
            history = HistoryChoice.LAST_6_MONTHS,
            confirmed = true,
            period = 3,
            lowerBoundMs = 5L,
            frozen = setOf("doc/1"),
            marks = listOf(DocMark("doc/1", ReceivedEnd.DELIVERED)),
            received = listOf(ReceivedHash("abc", ReceivedEnd.DELIVERED)),
            staged = listOf(held),
            pauses = pauses,
            sourcePaused = true,
        )
        val next = firstSetupAgain(on)
        kotlin.test.assertFalse(next.confirmed, "nothing comes in until the owner confirms")
        kotlin.test.assertFalse(next.reconfirm, "the new journal holds none of them, so S17 would not be true")
        assertEquals(1, next.period, "the tile reads `ready to set up` again")
        assertEquals(emptySet(), next.frozen, "the count is taken again")
        assertEquals(emptyList(), next.marks)
        assertEquals(emptyList(), next.received, "nothing is already in the new journal")
        assertEquals(
            ShotFate.COUNT,
            shotFate(ShotRead.Shot(ShotKind.PNG, null, 10, 10), 1_000L, "abc", next.received, 0L, emptyList(), next.confirmed),
            "a screenshot the last journal had is counted for this one",
        )
        assertEquals("content://folder", next.treeUri)
        assertEquals(HistoryChoice.LAST_6_MONTHS, next.history)
        assertEquals(listOf(held), next.staged, "held screenshots go to the journal paired now")
        assertEquals(pauses, next.pauses)
        kotlin.test.assertTrue(next.sourcePaused, "the owner's pause stays")
    }

    @Test
    fun aBookWrittenWithJournalNamesKeepsOnlyWhatItsConfirmedJournalHad() {
        val legacy = listOf(
            "tree\tcontent%3A%2F%2Ffolder",
            "confirmed\t1",
            "received\tjournal-a\tabc\tDELIVERED",
            "received\tjournal-b\tdef\tDELIVERED",
            "journal\tjournal-b",
        ).joinToString("\n")
        val book = decodeBook(legacy)
        assertEquals(listOf(ReceivedHash("def", ReceivedEnd.DELIVERED)), book.received)
        kotlin.test.assertTrue(book.confirmed)
        assertEquals(book, decodeBook(encodeBook(book)))
        kotlin.test.assertFalse(encodeBook(book).contains("journal"))
        assertEquals(emptyList(), decodeBook(legacy.replace("journal\tjournal-b", "")).received, "no confirmed journal, nothing it had")
    }

    @Test
    fun theFolderFactNamesThePathInsideStorage() {
        assertEquals("DCIM › Screenshots", screenshotsFolderPath("primary:DCIM/Screenshots", "Screenshots"))
        assertEquals("Download › mixed", screenshotsFolderPath("primary:Download/mixed/", "mixed"))
        assertEquals("Screenshots", screenshotsFolderPath("primary:", "Screenshots"))
        assertEquals("Screenshots", screenshotsFolderPath(null, "Screenshots"))
    }
}
