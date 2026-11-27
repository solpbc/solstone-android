// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.garmin

import app.solstone.observer.phone.wear.PhoneWatchContactStore
import app.solstone.observer.phone.wear.WatchPlatform
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GarminWatchContactStoreTest {

    @Test
    fun freshStoreIsInactiveAndHasNoContact() {
        val dir = Files.createTempDirectory("garmin-contact-fresh").toFile()
        try {
            val store = PhoneWatchContactStore(dir)
            assertFalse(store.isGarminActivated())
            assertFalse(store.hasContact())
            assertNull(store.read())
            assertNull(store.lastContactEpochMs())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun garminActivationAndContactPersistAtomicallyInUnifiedWatchStore() {
        val dir = Files.createTempDirectory("garmin-contact-persist").toFile()
        try {
            val store = PhoneWatchContactStore(dir)
            store.recordGarminActivated()
            assertTrue(store.isGarminActivated())
            assertTrue(store.hasContact())
            val activated = PhoneWatchContactStore(dir).read()
            assertEquals(WatchPlatform.GARMIN, activated?.platform)
            assertEquals(false, activated?.hasWatchContact)
            assertNull(store.lastContactEpochMs())

            store.recordGarminContact(
                epochMs = 1795850000_000L,
                version = 1,
                waitingSlabs = 14,
                deviceId = 3646641882L,
                deviceName = "Forerunner 970",
                storageFull = true,
                lastEndReason = 4,
            )

            val reloaded = PhoneWatchContactStore(dir).read()
            assertEquals(WatchPlatform.GARMIN, reloaded?.platform)
            assertEquals(true, reloaded?.hasWatchContact)
            assertEquals(1795850000_000L, reloaded?.at)
            assertEquals(1, reloaded?.version)
            assertEquals(14, reloaded?.sealedWaiting)
            assertEquals(3646641882L, reloaded?.deviceId)
            assertEquals("Forerunner 970", reloaded?.deviceName)
            assertEquals(true, reloaded?.storageFull)
            assertEquals(4, reloaded?.lastEndReason)

            // Pure background drain completion clears storageFull and waitingSlabs while preserving device + end reason
            store.recordGarminContact(
                epochMs = 1795850060_000L,
                version = 1,
                waitingSlabs = 0,
                deviceId = 3646641882L,
                deviceName = "Forerunner 970",
                storageFull = false,
            )
            val drained = PhoneWatchContactStore(dir).read()
            assertEquals(0, drained?.sealedWaiting)
            assertEquals(false, drained?.storageFull)
            assertEquals(4, drained?.lastEndReason)
            assertEquals("Forerunner 970", drained?.deviceName)

            store.clearContact()
            assertFalse(PhoneWatchContactStore(dir).isGarminActivated())
            assertNull(PhoneWatchContactStore(dir).read())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun activationLinkMatchesOnlyCanonicalGoSolstoneGarminRoute() {
        assertTrue(GarminEnvironmentProbe.isGarminActivationLink("https://go.solstone.app/garmin"))
        assertTrue(GarminEnvironmentProbe.isGarminActivationLink("https://go.solstone.app/garmin/"))
        assertFalse(GarminEnvironmentProbe.isGarminActivationLink("https://go.solstone.app/p#abc"))
        assertFalse(GarminEnvironmentProbe.isGarminActivationLink("https://go.solstone.app/j#abc"))
        assertFalse(GarminEnvironmentProbe.isGarminActivationLink("http://go.solstone.app/garmin"))
        assertFalse(GarminEnvironmentProbe.isGarminActivationLink("https://evil.example/garmin"))
        assertFalse(GarminEnvironmentProbe.isGarminActivationLink(null))
    }
}
