// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.core.obsidian

import kotlin.test.*

class ObsidianDeliveryPolicyTest {
    @Test fun vaultListUsesTheIngestReadProtocolBeforeAuthorizingDelivery() {
        val journal = object : app.solstone.core.pl.PlHttpClient {
            override fun request(method: String, path: String, headers: Map<String, String>,
                body: ByteArray?, maxResponseBytes: Int): app.solstone.core.pl.HttpResponse {
                assertEquals("GET", method)
                assertEquals("/app/devices/ingest/obsidian/vaults", path)
                assertNull(body)
                assertTrue(maxResponseBytes <= 64 * 1024)
                // Journal validate_protocol rejects this read route without protocol 3.
                return if (headers["X-Solstone-Protocol-Version"] == "3")
                    app.solstone.core.pl.HttpResponse(200, emptyMap(), """{"vaults":[]}""".toByteArray())
                else app.solstone.core.pl.HttpResponse(400, emptyMap(), """{"reason_code":"protocol_version_required"}""".toByteArray())
            }
        }
        assertEquals(400, journal.request("GET", "/app/devices/ingest/obsidian/vaults", emptyMap(), null, 64 * 1024).status)
        assertEquals(emptyList(), fetchOtherObsidianVaults(journal))
    }

    @Test fun capabilityDistinguishesOlderFromFailed() {
        assertEquals(true, parseObsidianCapability(200, """{"capabilities":["segments","obsidian_notes"]}"""))
        assertEquals(false, parseObsidianCapability(200, """{"capabilities":["segments"]}"""))
        assertEquals(false, parseObsidianCapability(200, "{}"))
        assertNull(parseObsidianCapability(503, "{}"))
        assertNull(parseObsidianCapability(200, """{"capabilities":null}"""))
        assertNull(parseObsidianCapability(200, """{"capabilities":[1]}"""))
        assertNull(parseObsidianCapability(200, "not json"))
    }
    @Test fun sameNameWarningUsesTypedDeviceAndDateAndUnknownListCannotAuthorize() {
        val line = """{"vaults":[{"vault":"Notes","device":"studio","last_synced_at":"2026-11-08T09:44:33-07:00"}]}"""
        val list = requireNotNull(parseOtherObsidianVaults(200, line))
        assertEquals(ExistingObsidianVault("Notes", "studio", "2026-11-08T09:44:33-07:00"), list.single())
        assertEquals(emptyList(), parseOtherObsidianVaults(404, ""))
        assertEquals(emptyList(), parseOtherObsidianVaults(200, """{"vaults":[]}"""))
        assertNull(parseOtherObsidianVaults(503, line))
        assertNull(parseOtherObsidianVaults(200, "{}"))
        assertNull(parseOtherObsidianVaults(200, line.replace("studio", "").replace("\"device\":\"\"", "\"device\":1")))
        assertNull(parseOtherObsidianVaults(200, line.replace("2026-11-08T09:44:33-07:00", "unknown")))
        assertEquals(null, parseOtherObsidianVaults(200, line.replace("\"studio\"", "null"))?.single()?.device)
    }
}
