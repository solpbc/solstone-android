// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingPublisher

fun advertisedDirectEndpoints(entries: List<Map<String, Any?>>): List<DirectEndpoint> =
    entries.map(::parseAdvertisedEndpoint).distinct()

private fun parseAdvertisedEndpoint(entry: Map<*, *>): DirectEndpoint {
    val host = (entry["ip"] as? String)?.trim() ?: error("missing endpoint ip")
    val number = entry["port"] as? Number ?: error("missing endpoint port")
    val port = number.toInt()
    require(host.isNotEmpty() && host.none { it.isWhitespace() || it.isISOControl() || it in "/\\?#@" })
    require(number.toDouble() == port.toDouble() && port in 1..65535)
    return DirectEndpoint(host, port)
}

fun parseLocalEndpoints(body: String): List<DirectEndpoint> {
    val root = parseJson(body) as? Map<*, *> ?: error("invalid endpoint response")
    require((root["v"] as? Number)?.toDouble() == 1.0)
    val endpoints = root["endpoints"] as? List<*> ?: error("missing endpoints")
    return endpoints.map {
        val entry = it as? Map<*, *> ?: error("invalid endpoint")
        require(entry["scope"] == "lan" || entry["scope"] == "vpn")
        parseAdvertisedEndpoint(entry)
    }.distinct()
}

/** Refresh through the authenticated connection that just became usable. */
fun refreshDirectEndpoints(
    client: PlHttpClient,
    publisher: PairingPublisher,
    expected: PairingGraphSnapshot.Committed,
    deliveringAddress: app.solstone.core.model.DirectEndpoint? = null,
    log: (String) -> Unit = { System.err.println(it) },
): Boolean {
    if (deliveringAddress?.host in setOf("127.0.0.1", "::1", "localhost")) return false
    return try {
        val response = client.request("GET", "/app/network/local-endpoints", emptyMap(), null, maxResponseBytes = 64 * 1024)
        if (response.status != 200) {
            log("address refresh: HTTP ${response.status}")
            false
        } else {
            val endpoints = parseLocalEndpoints(response.bodyText())
            if (endpoints.isEmpty()) false else {
                val retained = (endpoints + listOfNotNull(deliveringAddress)).distinct()
                when (publisher.replaceDirectEndpoints(expected, retained)) {
                    is GraphMutationResult.Applied -> true
                    is GraphMutationResult.Conflict -> false
                    else -> { log("address refresh: persistence failed"); false }
                }
            }
        }
    } catch (e: Exception) {
        log("address refresh: ${e.javaClass.simpleName}")
        false
    }
}

/** Optional connection jobs also refresh through the client they open. */
fun <T : PlHttpClient> openAddressRefreshingClient(
    publisher: PairingPublisher,
    pairing: app.solstone.core.identity.PairingGeneration,
    deliveringAddress: app.solstone.core.model.DirectEndpoint? = null,
    openClient: () -> T,
): T {
    val expected = (publisher.currentSnapshot() as? PairingGraphSnapshot.Committed)?.takeIf { it.pairing == pairing }
    val client = openClient()
    if (expected != null) refreshDirectEndpoints(client, publisher, expected, deliveringAddress)
    return client
}
