// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class RegisterThenDrainTest {
    @Test
    fun missingHandleRegistersPersistsThenDrains() {
        val client = FakePlHttpClient()
        val handle = "k".repeat(43)
        var registerCount = 0
        var persisted: String? = null
        var persistCount = 0
        var drainCount = 0
        var drainHandle: String? = null

        val outcome = registerThenDrain(
            client = client,
            existingHandle = null,
            register = {
                registerCount += 1
                handle
            },
            persist = {
                persistCount += 1
                persisted = it
            },
            drain = { _, receivedHandle ->
                drainCount += 1
                drainHandle = receivedHandle
                "drained"
            },
            onError = { error("unexpected error") },
        )

        assertEquals("drained", assertIs<RegisterDrainOutcome.Drained<String>>(outcome).result)
        assertEquals(1, registerCount)
        assertEquals(1, persistCount)
        assertEquals(handle, persisted)
        assertEquals(1, drainCount)
        assertEquals(handle, drainHandle)
    }

    @Test
    fun existingHandleSkipsRegisterAndDrains() {
        val client = FakePlHttpClient()
        var registerCount = 0
        var drainCount = 0
        var drainHandle: String? = null

        val outcome = registerThenDrain(
            client = client,
            existingHandle = "pre-existing-handle",
            register = {
                registerCount += 1
                "unused"
            },
            persist = { error("persist should not run") },
            drain = { _, receivedHandle ->
                drainCount += 1
                drainHandle = receivedHandle
                7
            },
            onError = { error("unexpected error") },
        )

        assertEquals(7, assertIs<RegisterDrainOutcome.Drained<Int>>(outcome).result)
        assertEquals(0, registerCount)
        assertEquals(1, drainCount)
        assertEquals("pre-existing-handle", drainHandle)
    }

    @Test
    fun registerFailureRetriesWithoutPersistOrDrain() {
        val client = FakePlHttpClient()
        val failure = IllegalStateException("nope")
        var persistCount = 0
        var drainCount = 0
        val errors = mutableListOf<Throwable>()

        val outcome = registerThenDrain(
            client = client,
            existingHandle = null,
            register = { throw failure },
            persist = { persistCount += 1 },
            drain = { _, _ ->
                drainCount += 1
                "drained"
            },
            onError = { errors += it },
        )

        assertSame(RegisterDrainOutcome.Retry, outcome)
        assertEquals(0, persistCount)
        assertEquals(0, drainCount)
        assertEquals(1, errors.size)
        assertSame(failure, errors.single())
    }

    @Test
    fun sameClientInstanceIsThreadedToRegisterAndDrain() {
        val client = FakePlHttpClient()
        var registerClient: PlHttpClient? = null
        var drainClient: PlHttpClient? = null

        val outcome = registerThenDrain(
            client = client,
            existingHandle = null,
            register = {
                registerClient = it
                "k".repeat(43)
            },
            persist = {},
            drain = { receivedClient, _ ->
                drainClient = receivedClient
                Unit
            },
            onError = { error("unexpected error") },
        )

        assertIs<RegisterDrainOutcome.Drained<Unit>>(outcome)
        assertSame(client, registerClient)
        assertSame(client, drainClient)
    }

    @Test
    fun persistFailureRetriesWithoutDrain() {
        val client = FakePlHttpClient()
        val failure = IllegalStateException("disk")
        var drainCount = 0
        val errors = mutableListOf<Throwable>()

        val outcome = registerThenDrain(
            client = client,
            existingHandle = null,
            register = { "k".repeat(43) },
            persist = { throw failure },
            drain = { _, _ ->
                drainCount += 1
                "drained"
            },
            onError = { errors += it },
        )

        assertSame(RegisterDrainOutcome.Retry, outcome)
        assertEquals(0, drainCount)
        assertEquals(1, errors.size)
        assertSame(failure, errors.single())
    }

    private class FakePlHttpClient : PlHttpClient {
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): HttpResponse =
            HttpResponse(200, emptyMap(), ByteArray(0))
    }
}
