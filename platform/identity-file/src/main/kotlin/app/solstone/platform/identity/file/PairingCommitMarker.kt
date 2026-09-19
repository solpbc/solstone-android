// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

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
            val map = text.lineSequence()
                .filter { it.isNotEmpty() }
                .associate { line ->
                    val parts = line.split('\t', limit = 2)
                    parts[0] to parts.getOrElse(1) { "" }
                }
            if (map["version"] != "1") return null
            val status = try {
                CommitMarkerStatus.valueOf(map["status"] ?: return null)
            } catch (_: IllegalArgumentException) {
                return null
            }
            val inFlightOp = try {
                CommitInFlightOp.valueOf(map["inFlightOp"] ?: "NONE")
            } catch (_: IllegalArgumentException) {
                CommitInFlightOp.NONE
            }
            val inFlightStep = try {
                CommitInFlightStep.valueOf(map["inFlightStep"] ?: "NONE")
            } catch (_: IllegalArgumentException) {
                CommitInFlightStep.NONE
            }
            val priorStatus = map["priorStatus"]?.let {
                try { CommitMarkerStatus.valueOf(it) } catch (_: IllegalArgumentException) { null }
            }
            return PairingCommitMarker(
                status = status,
                instanceId = map["instanceId"],
                clientCertSha256 = map["clientCertSha256"],
                hasDirectEndpoint = map["hasDirectEndpoint"]?.toBooleanStrictOrNull() ?: false,
                directAssociated = map["directAssociated"]?.toBooleanStrictOrNull() ?: false,
                hasRelayAccess = map["hasRelayAccess"]?.toBooleanStrictOrNull() ?: false,
                identityChecksum = map["identityChecksum"],
                credentialChecksum = map["credentialChecksum"],
                endpointChecksum = map["endpointChecksum"],
                inFlightOp = inFlightOp,
                inFlightStep = inFlightStep,
                priorStatus = priorStatus,
                priorInstanceId = map["priorInstanceId"],
                priorClientCertSha256 = map["priorClientCertSha256"],
                priorHasDirectEndpoint = map["priorHasDirectEndpoint"]?.toBooleanStrictOrNull() ?: false,
                priorDirectAssociated = map["priorDirectAssociated"]?.toBooleanStrictOrNull() ?: false,
                priorHasRelayAccess = map["priorHasRelayAccess"]?.toBooleanStrictOrNull() ?: false,
                priorIdentityChecksum = map["priorIdentityChecksum"],
                priorCredentialChecksum = map["priorCredentialChecksum"],
                priorEndpointChecksum = map["priorEndpointChecksum"],
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
    }
}
