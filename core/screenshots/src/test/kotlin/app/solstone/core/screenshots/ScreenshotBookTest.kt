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
}
