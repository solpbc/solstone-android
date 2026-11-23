// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.pl.MigrationClient
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MigrationCopyTest {
    private val now = Instant.parse("2026-11-23T12:00:00Z")
    private val nowMs = now.toEpochMilli()

    private fun client(lastSeenAt: String? = null, pairedAt: String? = null) = MigrationClient(
        cid = "sha256:${"a".repeat(64)}",
        displayLabel = "samsung SM-A366E",
        reported = null,
        lastSeenAt = lastSeenAt,
        pairedAt = pairedAt,
    )

    @Test
    fun lastReachedUsesClosedRelativeBucketsAndPrefersLastSeenOverPaired() {
        assertEquals(
            "last reached your journal just now",
            migrationClientActivityLine(client(lastSeenAt = now.minusSeconds(30).toString(), pairedAt = now.minusSeconds(86_400 * 5).toString()), nowMs),
        )
        assertEquals(
            "last reached your journal 1 minute ago",
            migrationClientActivityLine(client(lastSeenAt = now.minusSeconds(60).toString()), nowMs),
        )
        assertEquals(
            "last reached your journal 5 minutes ago",
            migrationClientActivityLine(client(lastSeenAt = now.minusSeconds(5 * 60).toString()), nowMs),
        )
        assertEquals(
            "last reached your journal 1 hour ago",
            migrationClientActivityLine(client(lastSeenAt = now.minusSeconds(3_600).toString()), nowMs),
        )
        assertEquals(
            "last reached your journal 3 hours ago",
            migrationClientActivityLine(client(lastSeenAt = now.minusSeconds(3 * 3_600).toString()), nowMs),
        )
        assertEquals(
            "last reached your journal 1 day ago",
            migrationClientActivityLine(client(lastSeenAt = now.minusSeconds(86_400).toString()), nowMs),
        )
        assertEquals(
            "last reached your journal 2 days ago",
            migrationClientActivityLine(client(lastSeenAt = now.minusSeconds(2 * 86_400).toString()), nowMs),
        )
    }

    @Test
    fun fallsBackToPairedWhenLastSeenAbsentMalformedOrFarInFuture() {
        assertEquals(
            "paired 2 days ago",
            migrationClientActivityLine(client(lastSeenAt = null, pairedAt = "2026-11-21T12:00:00+00:00"), nowMs),
        )
        assertEquals(
            "paired 1 hour ago",
            migrationClientActivityLine(client(lastSeenAt = "not-a-timestamp", pairedAt = now.minusSeconds(3_600).toString()), nowMs),
        )
        assertEquals(
            "paired 3 days ago",
            migrationClientActivityLine(client(lastSeenAt = now.plusSeconds(600).toString(), pairedAt = now.minusSeconds(3 * 86_400).toString()), nowMs),
        )
    }

    @Test
    fun clampsSmallFutureDriftAndOmitsWhenNeitherTimestampIsUsable() {
        assertEquals(
            "last reached your journal just now",
            migrationClientActivityLine(client(lastSeenAt = now.plusSeconds(120).toString()), nowMs),
        )
        assertNull(migrationClientActivityLine(client(lastSeenAt = null, pairedAt = null), nowMs))
        assertNull(migrationClientActivityLine(client(lastSeenAt = "  ", pairedAt = "bad"), nowMs))
        assertNull(migrationClientActivityLine(client(lastSeenAt = now.plusSeconds(600).toString(), pairedAt = now.plusSeconds(600).toString()), nowMs))
    }
}
