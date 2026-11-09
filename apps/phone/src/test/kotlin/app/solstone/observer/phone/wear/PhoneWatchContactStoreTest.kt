// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.wear

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneWatchContactStoreTest {
    @Test
    fun receiptContactDoesNotInventARemainingQueueCount() {
        val dir = Files.createTempDirectory("watch-contact-queue").toFile()
        val store = PhoneWatchContactStore(dir)
        store.recordContact(1000L, version = 9, sealedWaiting = 0)
        assertEquals(PhoneWatchContactStore.Contact(1000L, 9, 0), PhoneWatchContactStore(dir).read())
        store.recordContact(2000L)
        assertEquals(PhoneWatchContactStore.Contact(2000L, 9, null), PhoneWatchContactStore(dir).read())
        dir.resolve("phone_watch_contact.txt").writeText("bad contact")
        store.recordContact(3000L, version = 1, sealedWaiting = 3)
        assertEquals(PhoneWatchContactStore.Contact(3000L, 1, 3), PhoneWatchContactStore(dir).read())
    }

    @Test
    fun tracksContactLifecycle() {
        val dir = Files.createTempDirectory("watch-contact-test").toFile()
        val store = PhoneWatchContactStore(dir)

        assertFalse(store.hasContact())
        assertNull(store.lastContactEpochMs())

        val now = 1762682400000L
        store.recordContact(now)

        assertTrue(store.hasContact())
        assertEquals(now, store.lastContactEpochMs())

        store.clearContact()
        assertFalse(store.hasContact())
        assertNull(store.lastContactEpochMs())
    }
}
