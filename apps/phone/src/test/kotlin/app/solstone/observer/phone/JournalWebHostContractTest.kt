// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.diagnostics.formatDiagEvent
import app.solstone.core.pl.parseJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JournalWebHostContractTest {
    @Test
    fun vendoredContractProvidesProductAndAppendsToDefaultAgent() {
        val bytes = File("src/main/assets/journal-web-host/host-contract.json").readBytes()
        val result = parseJournalWebHostContract(bytes)
        assertTrue(result is JournalWebHostContractParse.Product)

        val root = parseJson(bytes.toString(Charsets.UTF_8)) as Map<*, *>
        val expectedProduct = root["user_agent_product"] as String
        val product = (result as JournalWebHostContractParse.Product).value
        assertEquals(expectedProduct, product)
        assertTrue(root.containsKey("initialization_script"))
        assertEquals("TestAgent/1.0 $expectedProduct", journalUserAgent("TestAgent/1.0", product))
        assertEquals("TestAgent/1.0", journalUserAgent("TestAgent/1.0", null))
    }

    @Test
    fun invalidContractsAreRefusedWithoutProduct() {
        listOf(
            ByteArray(0),
            "{".encodeToByteArray(),
            """{"version":2,"user_agent_product":"Ok/1"}""".encodeToByteArray(),
            """{"version":1.0,"user_agent_product":"Ok/1"}""".encodeToByteArray(),
            """{"version":1}""".encodeToByteArray(),
            """{"version":1,"user_agent_product":""}""".encodeToByteArray(),
            """{"version":1,"user_agent_product":"Bad Token"}""".encodeToByteArray(),
            "{\"version\":1,\"user_agent_product\":\"Bad\\nToken\"}".encodeToByteArray(),
        ).forEach { bytes ->
            assertSame(JournalWebHostContractParse.Refused, parseJournalWebHostContract(bytes))
        }
    }

    @Test
    fun invalidContractDiagnosticHasFixedRedactedFormat() {
        assertEquals(
            "kind=journal-browser class=host-contract outcome=invalid",
            formatDiagEvent(DiagEvent.JournalBrowser(eventClass = "host-contract", outcome = "invalid")),
        )
    }
}
