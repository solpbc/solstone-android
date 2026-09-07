// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

data class PairRequest(val csr: String, val deviceLabel: String) {
    fun toJson(): String = toJson(mapOf("csr" to csr, "device_label" to deviceLabel))
}

sealed class PairRelayAccess {
    data object Omitted : PairRelayAccess()
    data object PresentNull : PairRelayAccess()
    data class Object(val fields: Map<String, Any?>) : PairRelayAccess()
    data class NonObject(val json: Any?) : PairRelayAccess()
}

data class PairResponse(
    val caChain: List<String>,
    val clientCert: String,
    val instanceId: String,
    val homeLabel: String,
    val homeAttestation: String,
    val fingerprint: String,
    val localEndpoints: List<Map<String, Any?>> = emptyList(),
    val relayAccess: PairRelayAccess = PairRelayAccess.Omitted,
) {
    override fun toString(): String =
        "PairResponse(caChain=$caChain, clientCert=<redacted>, instanceId=$instanceId, homeLabel=$homeLabel, homeAttestation=<redacted>, fingerprint=$fingerprint, localEndpoints=$localEndpoints, relayAccess=<redacted>)"

    companion object {
        fun fromJson(text: String): PairResponse {
            val root = parseJson(text) as? Map<*, *> ?: throw IllegalArgumentException("pair response must be an object")
            val caChain = (root["ca_chain"] as? List<*>)
                ?.map { it as? String ?: throw IllegalArgumentException("ca_chain entries must be strings") }
                ?: throw IllegalArgumentException("pair response missing CA chain")
            if (caChain.isEmpty()) {
                throw IllegalArgumentException("pair response missing CA chain")
            }
            val endpoints = (root["local_endpoints"] as? List<*>)
                ?.map { item ->
                    val map = item as? Map<*, *> ?: throw IllegalArgumentException("local_endpoints entries must be objects")
                    LinkedHashMap<String, Any?>().also { out ->
                        for ((key, value) in map) {
                            if (key !is String) {
                                throw IllegalArgumentException("local endpoint keys must be strings")
                            }
                            out[key] = value
                        }
                    }
                }
                ?: emptyList()
            val relayAccess = if (!root.containsKey("relay_access")) {
                PairRelayAccess.Omitted
            } else {
                when (val raw = root["relay_access"]) {
                    null -> PairRelayAccess.PresentNull
                    is Map<*, *> -> {
                        val map = LinkedHashMap<String, Any?>()
                        for ((key, value) in raw) {
                            if (key is String) {
                                map[key] = value
                            }
                        }
                        PairRelayAccess.Object(map)
                    }
                    else -> PairRelayAccess.NonObject(raw)
                }
            }
            return PairResponse(
                caChain = caChain,
                clientCert = requiredString(root, "client_cert"),
                instanceId = requiredString(root, "instance_id"),
                homeLabel = root["home_label"] as? String ?: "",
                homeAttestation = requiredString(root, "home_attestation"),
                fingerprint = requiredString(root, "fingerprint"),
                localEndpoints = endpoints,
                relayAccess = relayAccess,
            )
        }

        private fun requiredString(root: Map<*, *>, key: String): String =
            root[key] as? String ?: throw IllegalArgumentException("pair response missing $key")
    }
}

data class StatusResponse(val bodyText: String)
