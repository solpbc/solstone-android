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
            received = listOf(ReceivedHash("journal", "abc", ReceivedEnd.REFUSED_TYPE)),
            staged = listOf(StagedShot("doc/1", "abc", "2026-11-03T14:32:07-07:00", "platform", "20261103/screenshots/leaf", 99L)),
            marks = listOf(DocMark("doc/1", ReceivedEnd.DELIVERED)),
            lastCheckedMs = 50L,
            journalId = "journal",
            sourcePaused = true,
        )
        assertEquals(book, decodeBook(encodeBook(book)))
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
        assertEquals(ShotFate.SKIP, shotFate(ShotRead.NotScreenshot, 1_000L, null, null, emptyList(), 0L, emptyList(), true))
        assertEquals(ShotFate.COUNT, shotFate(shot, 1_000L, "abc", "j", emptyList(), 0L, emptyList(), false))
        assertEquals(ShotFate.SEND, shotFate(shot, 1_000L, "abc", "j", emptyList(), 0L, emptyList(), true))
        assertEquals(ShotFate.SKIP, shotFate(shot, 1_000L, "abc", "j", emptyList(), 5_000L, emptyList(), true))
        assertEquals(
            ShotFate.ALREADY,
            shotFate(shot, 1_000L, "abc", "j", listOf(ReceivedHash("j", "abc", ReceivedEnd.DELIVERED)), 0L, emptyList(), true),
        )
        assertEquals(
            ShotFate.UNREADABLE,
            shotFate(ShotRead.Ended(ShotKind.PNG, ShotEnd.UNREADABLE), 1_000L, null, null, emptyList(), 0L, emptyList(), false),
        )
        assertEquals(
            ShotFate.REFUSED_SIZE,
            shotFate(ShotRead.Ended(ShotKind.JPEG, ShotEnd.REFUSED_SIZE), 1_000L, null, null, emptyList(), 0L, emptyList(), true),
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
    fun aDifferentJournalAsksHowFarBackAgainAndAnUnpairDoesNot() {
        val held = StagedShot("doc/2", "def", "2026-11-03T14:32:07-07:00", "platform", "20261103/screenshots/leaf2", 99L)
        val on = ScreenshotBook(
            treeUri = "content://folder",
            confirmed = true,
            frozen = setOf("doc/1"),
            marks = listOf(DocMark("doc/1", ReceivedEnd.DELIVERED)),
            received = listOf(ReceivedHash("first", "abc", ReceivedEnd.DELIVERED)),
            staged = listOf(held),
            journalId = "first",
        )
        kotlin.test.assertNull(followJournal(on, "first"))
        kotlin.test.assertNull(followJournal(on, null), "unpaired")
        val next = followJournal(on, "second")!!
        kotlin.test.assertFalse(next.confirmed)
        kotlin.test.assertFalse(next.reconfirm, "the new journal has none of them, so S17 would not be true")
        assertEquals(emptySet(), next.frozen)
        assertEquals(on.received, next.received, "what the first journal received is still remembered")
        assertEquals(listOf(held), next.staged, "held screenshots survive and go to the journal paired when sent")
        kotlin.test.assertNull(next.journalId)
    }

    @Test
    fun aConfirmMadeUnpairedBelongsToTheFirstJournalPaired() {
        val unpaired = ScreenshotBook(treeUri = "content://folder", confirmed = true)
        assertEquals("first", followJournal(unpaired, "first")!!.journalId)
        kotlin.test.assertTrue(followJournal(unpaired, "first")!!.confirmed)
        kotlin.test.assertNull(followJournal(ScreenshotBook(treeUri = "content://folder"), "first"), "not on yet")
    }

    @Test
    fun theFolderFactNamesThePathInsideStorage() {
        assertEquals("DCIM › Screenshots", screenshotsFolderPath("primary:DCIM/Screenshots", "Screenshots"))
        assertEquals("Download › mixed", screenshotsFolderPath("primary:Download/mixed/", "mixed"))
        assertEquals("Screenshots", screenshotsFolderPath("primary:", "Screenshots"))
        assertEquals("Screenshots", screenshotsFolderPath(null, "Screenshots"))
    }
}
