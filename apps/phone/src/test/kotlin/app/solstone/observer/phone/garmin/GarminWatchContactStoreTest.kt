// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.garmin

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
            val store = GarminWatchContactStore(dir)
            assertFalse(store.isActivated())
            assertFalse(store.hasContact())
            assertNull(store.read())
            assertNull(store.lastContactEpochMs())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun activationAndContactPersistAtomically() {
        val dir = Files.createTempDirectory("garmin-contact-persist").toFile()
        try {
            val store = GarminWatchContactStore(dir)
            store.markActivated()
            assertTrue(store.isActivated())
            assertFalse(store.hasContact())

            store.recordContact(
                epochMs = 1795850000_000L,
                version = 1,
                waitingSlabs = 14,
                deviceId = 3646641882L,
                deviceName = "Forerunner 970",
                storageFull = false,
                lastEndReason = 1,
            )

            val reloaded = GarminWatchContactStore(dir).read()
            assertEquals(true, reloaded?.activated)
            assertEquals(1795850000_000L, reloaded?.at)
            assertEquals(1, reloaded?.version)
            assertEquals(14, reloaded?.waitingSlabs)
            assertEquals(3646641882L, reloaded?.deviceId)
            assertEquals("Forerunner 970", reloaded?.deviceName)
            assertEquals(false, reloaded?.storageFull)
            assertEquals(1, reloaded?.lastEndReason)

            store.clearContact()
            assertFalse(GarminWatchContactStore(dir).isActivated())
            assertNull(GarminWatchContactStore(dir).read())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun activationLinkMatchesOnlyCanonicalGoSolstoneGarminRoute() {
        assertTrue(GarminEnvironmentProbe.isGarminActivationLink("https://go.solstone.app/garmin"))
        assertTrue(GarminEnvironmentProbe.isGarminActivationLink("https://go.solstone.app/garmin/"))
        assertTrue(GarminEnvironmentProbe.isGarminActivationLink("https://go.solstone.app/garmin?src=watch"))
        assertFalse(GarminEnvironmentProbe.isGarminActivationLink("https://go.solstone.app/p#abc"))
        assertFalse(GarminEnvironmentProbe.isGarminActivationLink("https://go.solstone.app/j#abc"))
        assertFalse(GarminEnvironmentProbe.isGarminActivationLink("http://go.solstone.app/garmin"))
        assertFalse(GarminEnvironmentProbe.isGarminActivationLink("https://evil.example/garmin"))
        assertFalse(GarminEnvironmentProbe.isGarminActivationLink(null))
    }
}
