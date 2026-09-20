// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClientsRevokeApiTest {

    private class RecordingClient(
        private val status: Int,
        private val body: String = "",
    ) : PlHttpClient {
        var seenMethod: String? = null
        var seenPath: String? = null

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse {
            seenMethod = method
            seenPath = path
            return HttpResponse(status, emptyMap(), this.body.encodeToByteArray())
        }
    }

    @Test
    fun revokeAddressesTheDevicesOwnRecordAndReportsRemoved() {
        val client = RecordingClient(200, """{"unpaired":"sha256:aa"}""")
        val cid = "sha256:" + "ab".repeat(32)
        assertEquals(ClientRevokeResult.Removed, revokeClient(client, cid))
        assertEquals("DELETE", client.seenMethod)
        // The colon is the only byte a path segment cannot carry literally.
        assertEquals("/app/network/api/clients/sha256%3A" + "ab".repeat(32), client.seenPath)
    }

    @Test
    fun alreadyGoneIsTheStateTheOwnerAskedFor() {
        val client = RecordingClient(404)
        assertEquals(ClientRevokeResult.NotFound, revokeClient(client, "sha256:" + "0".repeat(64)))
    }

    @Test
    fun aRefusalCarriesItsStatusRatherThanReadingAsSuccess() {
        val client = RecordingClient(503, "unavailable")
        val result = revokeClient(client, "sha256:" + "f".repeat(64))
        assertTrue(result is ClientRevokeResult.Failure)
        assertEquals(503, (result as ClientRevokeResult.Failure).status)
    }

    /**
     * ⛔ A cid that is not a lowercase sha-256 digest is refused rather than escaped, so nothing
     * of another shape can be smuggled into the path.
     */
    @Test
    fun anIdentifierOfTheWrongShapeIsRefusedNotEncoded() {
        assertFailsWith<IllegalArgumentException> { encodeCidPathSegment("sha1:abc") }
        assertFailsWith<IllegalArgumentException> { encodeCidPathSegment("sha256:../../admin") }
        assertFailsWith<IllegalArgumentException> { encodeCidPathSegment("sha256:" + "AB".repeat(32)) }
        assertFailsWith<IllegalArgumentException> { encodeCidPathSegment("sha256:" + "ab".repeat(31)) }
    }
}
