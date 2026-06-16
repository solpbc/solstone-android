// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

data class PairRequest(val csr: String, val deviceLabel: String) {
    fun toJson(): String = toJson(mapOf("csr" to csr, "device_label" to deviceLabel))
}

data class PairResponse(
    val caChain: List<String>,
    val clientCert: String,
    val instanceId: String,
    val homeLabel: String,
    val homeAttestation: String,
    val fingerprint: String,
    val localEndpoints: List<Map<String, Any?>>,
) {
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
            return PairResponse(
                caChain = caChain,
                clientCert = requiredString(root, "client_cert"),
                instanceId = requiredString(root, "instance_id"),
                homeLabel = root["home_label"] as? String ?: "",
                homeAttestation = requiredString(root, "home_attestation"),
                fingerprint = requiredString(root, "fingerprint"),
                localEndpoints = endpoints,
            )
        }

        private fun requiredString(root: Map<*, *>, key: String): String =
            root[key] as? String ?: throw IllegalArgumentException("pair response missing $key")
    }
}

data class StatusResponse(val bodyText: String)
