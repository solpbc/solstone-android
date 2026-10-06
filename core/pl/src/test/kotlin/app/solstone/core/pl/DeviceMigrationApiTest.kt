// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DeviceMigrationApiTest {
    @Test
    fun pinnedMigrationResourcesAreOfflineAndHashBound() {
        val adoption = parseJson(resource("device-migration/adoption.json")) as Map<*, *>
        assertEquals("1e432dba3ecdfa43789c25f97077fdc3e71fab59", adoption["authority_commit"])
        val files = adoption["files"] as List<*>
        files.forEach { raw ->
            val entry = raw as Map<*, *>
            val resourceName = (entry["path"] as String).removePrefix("contracts/device-migration/")
            assertEquals(entry["sha256"], sha256(resource("device-migration/$resourceName").toByteArray()))
        }
        val vectors = parseJson(resource("device-migration/v1.vectors.json")) as Map<*, *>
        val replace = vectors["vectors"] as Map<*, *>
        val request = replace["replace_request"] as Map<*, *>
        val exact = "{\"protocol_version\":1,\"operation_id\":\"123e4567-e89b-42d3-a456-426614174004\",\"choice\":\"replace_device\",\"replaces_cid\":\"sha256:${"c".repeat(64)}\"}"
        assertContentEquals(exact.toByteArray(), canonicalMigrationDecisionPayload(
            request["operation_id"] as String,
            PairingMigrationChoice.REPLACE,
            request["replaces_cid"] as String,
        ))
        assertEquals(5, (replace["migration_states"] as List<*>).size)
    }

    @Test
    fun listIgnoresUnrelatedPropertiesAndKeepsDuplicateLabelsAsSeparateCidRows() {
        val self = "sha256:${"a".repeat(64)}"
        val other = "sha256:${"b".repeat(64)}"
        val body = toJson(
            mapOf(
                "clients" to listOf(
                    mapOf("cid" to self, "display_label" to "Shared", "unrelated_row_property" to true),
                    mapOf("cid" to other, "display_label" to "Shared", "reported" to mapOf("name" to "tablet", "future_field" to true)),
                ),
                "unrelated_root_property" to mapOf("revision" to 2),
            ),
        )
        val client = RecordingClient { method, path, _, _ ->
            assertEquals("GET", method)
            assertEquals("/app/network/api/clients", path)
            HttpResponse(200, emptyMap(), body.toByteArray())
        }
        val result = assertIs<MigrationApiResult.Success<List<MigrationClient>>>(getMigrationClients(client))
        assertEquals(
            listOf(
                MigrationClient(self, "Shared", null),
                MigrationClient(other, "Shared", ClientReportedDescription(name = "tablet")),
            ),
            result.value,
        )
    }

    @Test
    fun putUsesCanonicalBodyAndOnlyPinnedDefiniteRefusalsAreCertain() {
        val expected = "{\"protocol_version\":1,\"operation_id\":\"123e4567-e89b-42d3-a456-426614174004\",\"choice\":\"new_device\"}"
        val client = RecordingClient { method, path, headers, body ->
            assertEquals("PUT", method)
            assertEquals("/app/network/api/clients/self/migration", path)
            assertEquals("application/json", headers["Content-Type"])
            assertContentEquals(expected.toByteArray(), body)
            HttpResponse(400, emptyMap(), "{\"reason_code\":\"migration_request_invalid\"}".toByteArray())
        }
        assertIs<MigrationApiResult.Refused>(
            putMigrationDecision(client, canonicalMigrationDecisionPayload(
                "123e4567-e89b-42d3-a456-426614174004", PairingMigrationChoice.KEEP_BOTH, null,
            )),
        )
        val otherRefusal = RecordingClient { _, _, _, _ ->
            HttpResponse(400, emptyMap(), "{\"reason_code\":\"migration_operation_conflict\"}".toByteArray())
        }
        assertIs<MigrationApiResult.Unknown>(putMigrationDecision(
            otherRefusal,
            canonicalMigrationDecisionPayload("123e4567-e89b-42d3-a456-426614174004", PairingMigrationChoice.KEEP_BOTH, null),
        ))
    }

    @Test
    fun onlyMissingPairedDeviceResponseProvesTargetUnavailable() {
        val body = canonicalMigrationDecisionPayload(
            "123e4567-e89b-42d3-a456-426614174004",
            PairingMigrationChoice.REPLACE,
            "sha256:${"c".repeat(64)}",
        )
        val missingTarget = RecordingClient { _, _, _, _ ->
            HttpResponse(404, emptyMap(), "{\"reason_code\":\"paired_device_not_found\"}".toByteArray())
        }
        val unavailable = assertIs<MigrationApiResult.TargetUnavailable>(putMigrationDecision(missingTarget, body))
        assertEquals(404, unavailable.status)
        assertEquals("paired_device_not_found", unavailable.reasonCode)

        val conflict = RecordingClient { _, _, _, _ ->
            HttpResponse(409, emptyMap(), "{\"reason_code\":\"migration_target_conflict\"}".toByteArray())
        }
        val unresolved = assertIs<MigrationApiResult.Unknown>(putMigrationDecision(conflict, body))
        assertEquals(409, unresolved.status)
        assertEquals("migration_target_conflict", unresolved.reasonCode)

        val wrongStatus = RecordingClient { _, _, _, _ ->
            HttpResponse(400, emptyMap(), "{\"reason_code\":\"paired_device_not_found\"}".toByteArray())
        }
        assertIs<MigrationApiResult.Unknown>(putMigrationDecision(wrongStatus, body))
    }

    @Test
    fun listingTransportExceptionHasNoHttpStatus() {
        val client = RecordingClient { _, _, _, _ -> error("offline") }
        assertEquals(MigrationApiResult.Unknown(null), getMigrationClients(client))
    }

    @Test
    fun pinnedMigrationStateAndDecisionResponsesUseTheirDeclaredShapes() {
        val vectors = parseJson(resource("device-migration/v1.vectors.json")) as Map<*, *>
        val all = vectors["vectors"] as Map<*, *>
        val stateVector = ((all["migration_states"] as List<*>)[1] as Map<*, *>)
        val stateClient = RecordingClient { method, path, _, _ ->
            assertEquals("GET", method)
            assertEquals("/app/network/api/clients/self/migration", path)
            HttpResponse(200, emptyMap(), toJson(stateVector).toByteArray())
        }
        val state = assertIs<MigrationApiResult.Success<MigrationStateResponse>>(getMigrationState(stateClient)).value
        assertEquals("pending", state.state)
        assertEquals("123e4567-e89b-42d3-a456-426614174000", state.operationId)

        val expectedDecision = all["decision_response"] as Map<*, *>
        val decisionClient = RecordingClient { method, _, _, _ ->
            assertEquals("PUT", method)
            HttpResponse(200, emptyMap(), toJson(expectedDecision).toByteArray())
        }
        val decision = assertIs<MigrationApiResult.Success<MigrationDecisionResponse>>(
            putMigrationDecision(
                decisionClient,
                canonicalMigrationDecisionPayload(
                    "123e4567-e89b-42d3-a456-426614174004",
                    PairingMigrationChoice.REPLACE,
                    "sha256:${"c".repeat(64)}",
                ),
            ),
        ).value
        assertEquals("123e4567-e89b-42d3-a456-426614174004", decision.operationId)
        assertEquals("replaced_device", decision.state)
        assertEquals("sha256:${"b".repeat(64)}", decision.cid)
        assertEquals("sha256:${"c".repeat(64)}", decision.replacedCid)
    }

    @Test
    fun duplicateJsonKeysAndMalformedListsStayUnknown() {
        assertNull(runCatching { parseJson("{\"clients\":[],\"clients\":[]}") }.getOrNull())
        val duplicate = RecordingClient { _, _, _, _ ->
            HttpResponse(200, emptyMap(), "{\"clients\":[{}]}".toByteArray())
        }
        assertIs<MigrationApiResult.Unknown>(getMigrationClients(duplicate))

        val validCid = "sha256:${"d".repeat(64)}"
        listOf(
            mapOf("cid" to validCid.uppercase(), "display_label" to "Device"),
            mapOf("cid" to validCid, "display_label" to "  "),
            mapOf("cid" to validCid),
        ).forEach { row ->
            val malformed = RecordingClient { _, _, _, _ ->
                HttpResponse(200, emptyMap(), toJson(mapOf("clients" to listOf(row))).toByteArray())
            }
            assertIs<MigrationApiResult.Unknown>(getMigrationClients(malformed))
        }

        val duplicateCid = "sha256:${"c".repeat(64)}"
        val duplicateRows = RecordingClient { _, _, _, _ ->
            HttpResponse(
                200,
                emptyMap(),
                toJson(mapOf("clients" to listOf(
                    mapOf("cid" to duplicateCid, "display_label" to "Phone"),
                    mapOf("cid" to duplicateCid, "display_label" to "Tablet"),
                ))).toByteArray(),
            )
        }
        assertIs<MigrationApiResult.Unknown>(getMigrationClients(duplicateRows))
    }

    private class RecordingClient(
        private val response: (String, String, Map<String, String>, ByteArray?) -> HttpResponse,
    ) : PlHttpClient {
        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse = response(method, path, headers, body)
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path)).bufferedReader().use { it.readText() }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }
}
