// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.PairingProvenance
import java.io.File
import java.security.MessageDigest

enum class CommitMarkerStatus {
    COMMITTED,
    ABSENT,
    UNCERTAIN,
    IN_FLIGHT,
}

enum class CommitInFlightOp {
    NONE,
    INSTALL,
    REPLACE,
    ACCESS_UPDATE,
    ADDRESS_UPDATE,
    REVOKE_ACCESS,
    FORGET,
}

enum class CommitInFlightStep {
    NONE,
    STAGING_WRITE,
    RENAME_REPLACE,
    DURABILITY_ACK,
    READ_BACK,
    COMMIT,
    CLEANUP,
}

data class PairingCommitMarker(
    val status: CommitMarkerStatus,
    val instanceId: String? = null,
    val clientCertSha256: String? = null,
    val hasDirectEndpoint: Boolean = false,
    val directAssociated: Boolean = false,
    val hasRelayAccess: Boolean = false,
    val identityChecksum: String? = null,
    val credentialChecksum: String? = null,
    val endpointChecksum: String? = null,
    val inFlightOp: CommitInFlightOp = CommitInFlightOp.NONE,
    val inFlightStep: CommitInFlightStep = CommitInFlightStep.NONE,
    val priorStatus: CommitMarkerStatus? = null,
    val priorInstanceId: String? = null,
    val priorClientCertSha256: String? = null,
    val priorHasDirectEndpoint: Boolean = false,
    val priorDirectAssociated: Boolean = false,
    val priorHasRelayAccess: Boolean = false,
    val priorIdentityChecksum: String? = null,
    val priorCredentialChecksum: String? = null,
    val priorEndpointChecksum: String? = null,
    val pairingProvenance: PairingProvenance? = null,
    val priorPairingProvenance: PairingProvenance? = null,
) {
    fun toSerializedString(): String = buildList {
        add("version\t1")
        add("status\t${status.name}")
        instanceId?.let { add("instanceId\t$it") }
        clientCertSha256?.let { add("clientCertSha256\t$it") }
        add("hasDirectEndpoint\t$hasDirectEndpoint")
        add("directAssociated\t$directAssociated")
        add("hasRelayAccess\t$hasRelayAccess")
        identityChecksum?.let { add("identityChecksum\t$it") }
        credentialChecksum?.let { add("credentialChecksum\t$it") }
        endpointChecksum?.let { add("endpointChecksum\t$it") }
        pairingProvenance?.let { add("pairingProvenance\t${it.name}") }
        add("inFlightOp\t${inFlightOp.name}")
        add("inFlightStep\t${inFlightStep.name}")
        priorStatus?.let { add("priorStatus\t${it.name}") }
        priorInstanceId?.let { add("priorInstanceId\t$it") }
        priorClientCertSha256?.let { add("priorClientCertSha256\t$it") }
        add("priorHasDirectEndpoint\t$priorHasDirectEndpoint")
        add("priorDirectAssociated\t$priorDirectAssociated")
        add("priorHasRelayAccess\t$priorHasRelayAccess")
        priorIdentityChecksum?.let { add("priorIdentityChecksum\t$it") }
        priorCredentialChecksum?.let { add("priorCredentialChecksum\t$it") }
        priorEndpointChecksum?.let { add("priorEndpointChecksum\t$it") }
        priorPairingProvenance?.let { add("priorPairingProvenance\t${it.name}") }
    }.joinToString(separator = "\n", postfix = "\n")

    companion object {
        fun absent(): PairingCommitMarker = PairingCommitMarker(
            status = CommitMarkerStatus.ABSENT,
            hasDirectEndpoint = false,
            directAssociated = false,
            hasRelayAccess = false,
        )

        fun uncertain(): PairingCommitMarker = PairingCommitMarker(
            status = CommitMarkerStatus.UNCERTAIN,
            hasDirectEndpoint = false,
            directAssociated = false,
            hasRelayAccess = false,
        )

        fun parse(file: File): PairingCommitMarker? {
            if (!file.exists()) return null
            return runCatching {
                parse(file.readText())
            }.getOrNull()
        }

        fun parse(text: String): PairingCommitMarker? {
            val map = linkedMapOf<String, String>()
            text.lineSequence().filter { it.isNotEmpty() }.forEach { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size != 2 || map.put(parts[0], parts[1]) != null) return null
            }
            if (map["version"] != "1") return null
            if (!FIELDS.containsAll(map.keys)) return null
            val status = try {
                CommitMarkerStatus.valueOf(map["status"] ?: return null)
            } catch (_: IllegalArgumentException) {
                return null
            }
            val inFlightOp = try {
                CommitInFlightOp.valueOf(map["inFlightOp"] ?: "NONE")
            } catch (_: IllegalArgumentException) {
                return null
            }
            val inFlightStep = try {
                CommitInFlightStep.valueOf(map["inFlightStep"] ?: "NONE")
            } catch (_: IllegalArgumentException) {
                return null
            }
            val priorStatus = map["priorStatus"]?.let {
                try { CommitMarkerStatus.valueOf(it) } catch (_: IllegalArgumentException) { return null }
            }
            fun boolean(name: String, default: Boolean = false): Boolean? =
                map[name]?.toBooleanStrictOrNull() ?: if (name !in map) default else null
            val hasDirectEndpoint = boolean("hasDirectEndpoint") ?: return null
            val directAssociated = boolean("directAssociated") ?: return null
            val hasRelayAccess = boolean("hasRelayAccess") ?: return null
            val priorHasDirectEndpoint = boolean("priorHasDirectEndpoint") ?: return null
            val priorDirectAssociated = boolean("priorDirectAssociated") ?: return null
            val priorHasRelayAccess = boolean("priorHasRelayAccess") ?: return null
            val pairingProvenance = map["pairingProvenance"]?.let {
                try { PairingProvenance.valueOf(it) } catch (_: IllegalArgumentException) { return null }
            }
            val priorPairingProvenance = map["priorPairingProvenance"]?.let {
                try { PairingProvenance.valueOf(it) } catch (_: IllegalArgumentException) { return null }
            }
            return PairingCommitMarker(
                status = status,
                instanceId = map["instanceId"],
                clientCertSha256 = map["clientCertSha256"],
                hasDirectEndpoint = hasDirectEndpoint,
                directAssociated = directAssociated,
                hasRelayAccess = hasRelayAccess,
                identityChecksum = map["identityChecksum"],
                credentialChecksum = map["credentialChecksum"],
                endpointChecksum = map["endpointChecksum"],
                pairingProvenance = pairingProvenance,
                inFlightOp = inFlightOp,
                inFlightStep = inFlightStep,
                priorStatus = priorStatus,
                priorInstanceId = map["priorInstanceId"],
                priorClientCertSha256 = map["priorClientCertSha256"],
                priorHasDirectEndpoint = priorHasDirectEndpoint,
                priorDirectAssociated = priorDirectAssociated,
                priorHasRelayAccess = priorHasRelayAccess,
                priorIdentityChecksum = map["priorIdentityChecksum"],
                priorCredentialChecksum = map["priorCredentialChecksum"],
                priorEndpointChecksum = map["priorEndpointChecksum"],
                priorPairingProvenance = priorPairingProvenance,
            )
        }


        fun checksumOf(file: File): String? {
            if (!file.exists()) return null
            return sha256Hex(file.readBytes())
        }

        fun checksumOf(bytes: ByteArray?): String? {
            if (bytes == null) return null
            return sha256Hex(bytes)
        }

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hexChars = CharArray(digest.size * 2)
            val digits = "0123456789abcdef"
            for (i in digest.indices) {
                val v = digest[i].toInt() and 0xff
                hexChars[i * 2] = digits[v ushr 4]
                hexChars[i * 2 + 1] = digits[v and 0x0f]
            }
            return String(hexChars)
        }

        private val FIELDS = setOf(
            "version", "status", "instanceId", "clientCertSha256", "hasDirectEndpoint", "directAssociated",
            "hasRelayAccess", "identityChecksum", "credentialChecksum", "endpointChecksum", "pairingProvenance",
            "inFlightOp", "inFlightStep", "priorStatus", "priorInstanceId", "priorClientCertSha256",
            "priorHasDirectEndpoint", "priorDirectAssociated", "priorHasRelayAccess", "priorIdentityChecksum",
            "priorCredentialChecksum", "priorEndpointChecksum", "priorPairingProvenance",
        )
    }
}
