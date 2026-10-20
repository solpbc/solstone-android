// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.PairingGeneration
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilePairingRefusalStoreTest {
    private val a = PairingGeneration("instance-a", "sha256:a")
    private val b = PairingGeneration("instance-b", "sha256:b")

    private fun store() = FilePairingRefusalStore(Files.createTempDirectory("refusal").resolve("pairing_refusal.json").toFile())

    @Test
    fun aRefusalHoldsForItsOwnPairingOnly() {
        val store = store()
        store.markRefused(a)
        assertTrue(store.refusedFor(a))
        assertFalse(store.refusedFor(b))
    }

    @Test
    fun anAuthorizedAnswerFromTheSamePairingClearsIt() {
        val store = store()
        store.markRefused(a)
        store.markAuthorized(a)
        assertFalse(store.refusedFor(a))
    }

    @Test
    fun anAuthorizedAnswerFromAnotherPairingLeavesItSet() {
        val store = store()
        store.markRefused(a)
        store.markAuthorized(b)
        assertTrue(store.refusedFor(a))
    }

    @Test
    fun aRefusalSurvivesReopening() {
        val file = Files.createTempDirectory("refusal").resolve("pairing_refusal.json").toFile()
        FilePairingRefusalStore(file).markRefused(a)
        assertTrue(FilePairingRefusalStore(file).refusedFor(a))
    }

    @Test
    fun anUnreadableRecordReadsAsNoRefusal() {
        val file = Files.createTempDirectory("refusal").resolve("pairing_refusal.json").toFile()
        file.writeText("{not json")
        assertFalse(FilePairingRefusalStore(file).refusedFor(a))
    }
}
