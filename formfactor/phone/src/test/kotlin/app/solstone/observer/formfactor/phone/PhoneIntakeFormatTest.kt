// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneIntakeFormatTest {
    private val zone = ZoneId.of("America/Denver")
    private val locale = Locale.US
    private val now = LocalDate.of(2026, 11, 9).atStartOfDay(zone).plusHours(15).toInstant().toEpochMilli()

    @Test
    fun durationFloorsAndUsesTheLockedWords() {
        assertEquals("less than a minute", formatDuration(0, locale))
        assertEquals("less than a minute", formatDuration(59, locale))
        assertEquals("1 minute", formatDuration(60, locale))
        assertEquals("40 minutes", formatDuration(40 * 60, locale))
        assertEquals("1 hour", formatDuration(90 * 60, locale))
        assertEquals("2 hours", formatDuration(120 * 60, locale))
    }

    @Test
    fun oneKilometreIsOneKilometerAndLessThanAMile() {
        val mm = 1_000_000L
        assertEquals("1 kilometer", formatDistance(mm, DistanceSystem.KILOMETERS, locale))
        assertEquals("less than a mile", formatDistance(mm, DistanceSystem.MILES, locale))
        assertEquals("less than a kilometer", formatDistance(999_000L, DistanceSystem.KILOMETERS, locale))
    }

    @Test
    fun unitedKingdomAndTheUnitedStatesUseMiles() {
        assertEquals(DistanceSystem.MILES, distanceSystem("UK"))
        assertEquals(DistanceSystem.MILES, distanceSystem("US"))
        assertEquals(DistanceSystem.KILOMETERS, distanceSystem("SI"))
    }

    @Test
    fun countsAreSingularAtOneAndGrouped() {
        assertEquals("1 photo", formatTotal(1, IntakeKind.PHOTOS, DistanceSystem.MILES, locale))
        assertEquals("2 photos", formatTotal(2, IntakeKind.PHOTOS, DistanceSystem.MILES, locale))
        assertEquals("1 event", formatTotal(1, IntakeKind.EVENTS, DistanceSystem.MILES, locale))
        assertEquals("1,200 items", formatTotal(1200, IntakeKind.ITEMS, DistanceSystem.MILES, locale))
    }

    @Test
    fun nothingYetOmitsLastTakenInAndALocationRowDoesNot() {
        assertEquals("nothing yet", formatSoFar(null, IntakeKind.DISTANCE, DistanceSystem.MILES, now, zone, locale))
        assertNull(formatLastTakenIn(null, now, zone))
        val still = PhoneIntakeReading(total = 0, startedAt = now, lastAt = now)
        assertEquals(
            "less than a mile, since november 9",
            formatSoFar(still, IntakeKind.DISTANCE, DistanceSystem.MILES, now, zone, locale),
        )
        assertEquals("just now", formatLastTakenIn(still, now, zone))
    }

    @Test
    fun startedKeepsItsYearOnlyWhenItIsNotThisYear() {
        val lastYear = LocalDate.of(2025, 3, 3).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals("march 3, 2025", formatStarted(lastYear, now, zone))
        assertEquals("november 9", formatStarted(now, now, zone))
    }

    @Test
    fun relativeTimeUsesTheCalendarDayForYesterday() {
        assertEquals("just now", formatRelative(now - 30_000L, now, zone))
        assertEquals("1 min ago", formatRelative(now - 60_000L, now, zone))
        assertEquals("1 hour ago", formatRelative(now - 90 * 60_000L, now, zone))
        assertEquals("2 hours ago", formatRelative(now - 2 * 3_600_000L, now, zone))
        assertEquals("23 hours ago", formatRelative(now - 23 * 3_600_000L, now, zone))
        val yesterdayMorning = LocalDate.of(2026, 11, 8).atStartOfDay(zone).plusHours(10).toInstant().toEpochMilli()
        assertEquals("yesterday", formatRelative(yesterdayMorning, now, zone))
        val twoDays = LocalDate.of(2026, 11, 7).atStartOfDay(zone).plusHours(10).toInstant().toEpochMilli()
        assertEquals("november 7", formatRelative(twoDays, now, zone))
    }
}
