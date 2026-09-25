// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.push

import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.atomicWriteOwnerOnly
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.io.File
import java.math.BigDecimal
import java.util.Base64

class PushRegistrationIdentity(
    val generation: PairingGeneration,
    val vapidKey: ByteArray,
    val distributorPackage: String,
    val distributorInstalledAt: Long?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PushRegistrationIdentity) return false
        return generation == other.generation &&
            distributorPackage == other.distributorPackage &&
            distributorInstalledAt == other.distributorInstalledAt &&
            vapidKey.contentEquals(other.vapidKey)
    }

    override fun hashCode(): Int {
        var result = generation.hashCode()
        result = 31 * result + vapidKey.contentHashCode()
        result = 31 * result + distributorPackage.hashCode()
        result = 31 * result + (distributorInstalledAt?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PushRegistrationIdentity(generation=$generation, distributorPackage=$distributorPackage)"
}

data class PushRegistrationAttempt(
    val identity: PushRegistrationIdentity,
    val startMillis: Long,
    val answered: Boolean,
    val unanswered: Boolean,
    val failure: String?,
)

data class PushRegistrationEndpoint(
    val url: String,
    val p256dh: String,
    val auth: String,
    val identity: PushRegistrationIdentity?,
    val posted: Boolean,
)

data class PendingPushDelete(
    val url: String,
    val generation: PairingGeneration,
    val failures: Int,
)

data class PushRegistrationState(
    val lastRegistered: PushRegistrationIdentity? = null,
    val attempt: PushRegistrationAttempt? = null,
    val ownerPick: String? = null,
    val endpoint: PushRegistrationEndpoint? = null,
    val unregisteredAt: Long? = null,
    val pendingDeletes: List<PendingPushDelete> = emptyList(),
    val delivery: PushDeliveryState = PushDeliveryState.Off,
) {
    companion object {
        val Empty = PushRegistrationState()
    }
}

object PushRegistrationFile {
    const val FILE_NAME = "push-registration.json"

    fun read(file: File, log: (String) -> Unit): PushRegistrationState {
        if (!file.exists()) {
            return PushRegistrationState.Empty
        }
        return try {
            val text = file.readText()
            val parsed = parseJson(text) as? Map<*, *> ?: throw IllegalArgumentException("Root not a map")
            val v = (parsed["v"] as? BigDecimal)?.toInt() ?: throw IllegalArgumentException("Missing or invalid v")
            if (v != 1) {
                throw IllegalArgumentException("Unsupported version $v")
            }
            decodeState(parsed)
        } catch (_: Exception) {
            log("kind=push reason=state_unreadable")
            PushRegistrationState.Empty
        }
    }

    fun write(file: File, state: PushRegistrationState, log: (String) -> Unit) {
        try {
            val map = encodeState(state)
            val json = toJson(map)
            atomicWriteOwnerOnly(file, json.toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            log("kind=push reason=internal")
        }
    }

    private fun encodeState(state: PushRegistrationState): Map<String, Any?> =
        mapOf(
            "v" to 1,
            "lastRegistered" to state.lastRegistered?.let(::encodeIdentity),
            "attempt" to state.attempt?.let { att ->
                mapOf(
                    "identity" to encodeIdentity(att.identity),
                    "startMillis" to att.startMillis,
                    "answered" to att.answered,
                    "unanswered" to att.unanswered,
                    "failure" to att.failure,
                )
            },
            "ownerPick" to state.ownerPick,
            "endpoint" to state.endpoint?.let { ep ->
                mapOf(
                    "url" to ep.url,
                    "p256dh" to ep.p256dh,
                    "auth" to ep.auth,
                    "identity" to ep.identity?.let(::encodeIdentity),
                    "posted" to ep.posted,
                )
            },
            "unregisteredAt" to state.unregisteredAt,
            "pendingDeletes" to state.pendingDeletes.map { del ->
                mapOf(
                    "url" to del.url,
                    "instanceId" to del.generation.instanceId,
                    "clientCertFingerprint" to del.generation.clientCertFingerprint,
                    "failures" to del.failures,
                )
            },
            "delivery" to encodeDelivery(state.delivery),
        )

    private fun encodeIdentity(id: PushRegistrationIdentity): Map<String, Any?> =
        mapOf(
            "instanceId" to id.generation.instanceId,
            "clientCertFingerprint" to id.generation.clientCertFingerprint,
            "vapid" to Base64.getUrlEncoder().withoutPadding().encodeToString(id.vapidKey),
            "distributor" to id.distributorPackage,
            "distributorInstalledAt" to id.distributorInstalledAt,
        )

    private fun encodeDelivery(delivery: PushDeliveryState): Map<String, Any?> =
        when (delivery) {
            PushDeliveryState.Ready -> mapOf("kind" to "Ready")
            PushDeliveryState.JournalHasNoPush -> mapOf("kind" to "JournalHasNoPush")
            PushDeliveryState.NoDeliveryApp -> mapOf("kind" to "NoDeliveryApp")
            PushDeliveryState.ChooseDeliveryApp -> mapOf("kind" to "ChooseDeliveryApp")
            is PushDeliveryState.WaitingForDelivery -> mapOf(
                "kind" to "WaitingForDelivery",
                "distributor" to delivery.distributorPackage,
                "unanswered" to delivery.unanswered,
            )
            PushDeliveryState.InsecureAddress -> mapOf("kind" to "InsecureAddress")
            PushDeliveryState.NotLinked -> mapOf("kind" to "NotLinked")
            is PushDeliveryState.Failed -> mapOf(
                "kind" to "Failed",
                "reason" to delivery.reason,
            )
            PushDeliveryState.Off -> mapOf("kind" to "Off")
        }

    private fun decodeState(map: Map<*, *>): PushRegistrationState {
        val lastRegistered = (map["lastRegistered"] as? Map<*, *>)?.let(::decodeIdentity)
        val attempt = (map["attempt"] as? Map<*, *>)?.let { att ->
            val id = decodeIdentity(att["identity"] as Map<*, *>)
            val startMillis = (att["startMillis"] as BigDecimal).toLong()
            val answered = att["answered"] as Boolean
            val unanswered = att["unanswered"] as Boolean
            val failure = att["failure"] as? String
            PushRegistrationAttempt(id, startMillis, answered, unanswered, failure)
        }
        val ownerPick = map["ownerPick"] as? String
        val endpoint = (map["endpoint"] as? Map<*, *>)?.let { ep ->
            val url = ep["url"] as String
            val p256dh = ep["p256dh"] as String
            val auth = ep["auth"] as String
            val id = (ep["identity"] as? Map<*, *>)?.let(::decodeIdentity)
            val posted = ep["posted"] as Boolean
            PushRegistrationEndpoint(url, p256dh, auth, id, posted)
        }
        val unregisteredAt = (map["unregisteredAt"] as? BigDecimal)?.toLong()
        val pendingDeletes = (map["pendingDeletes"] as? List<*>)?.mapNotNull { item ->
            val del = item as? Map<*, *> ?: return@mapNotNull null
            val url = del["url"] as String
            val instanceId = del["instanceId"] as String
            val cert = del["clientCertFingerprint"] as String
            val failures = (del["failures"] as BigDecimal).toInt()
            PendingPushDelete(url, PairingGeneration(instanceId, cert), failures)
        } ?: emptyList()
        val delivery = (map["delivery"] as? Map<*, *>)?.let(::decodeDelivery) ?: PushDeliveryState.Off

        return PushRegistrationState(
            lastRegistered = lastRegistered,
            attempt = attempt,
            ownerPick = ownerPick,
            endpoint = endpoint,
            unregisteredAt = unregisteredAt,
            pendingDeletes = pendingDeletes,
            delivery = delivery,
        )
    }

    private fun decodeIdentity(map: Map<*, *>): PushRegistrationIdentity {
        val instanceId = map["instanceId"] as String
        val cert = map["clientCertFingerprint"] as String
        val vapidStr = map["vapid"] as String
        val distributor = map["distributor"] as String
        val pad = (4 - (vapidStr.length % 4)) % 4
        val vapidBytes = Base64.getUrlDecoder().decode(vapidStr + "=".repeat(pad))
        val installedAt = decodeInstalledAt(map["distributorInstalledAt"])
        return PushRegistrationIdentity(PairingGeneration(instanceId, cert), vapidBytes, distributor, installedAt)
    }

    private fun decodeInstalledAt(value: Any?): Long? {
        val number = value as? BigDecimal ?: return null
        return try {
            number.longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun decodeDelivery(map: Map<*, *>): PushDeliveryState =
        when (map["kind"] as? String) {
            "Ready" -> PushDeliveryState.Ready
            "JournalHasNoPush" -> PushDeliveryState.JournalHasNoPush
            "NoDeliveryApp" -> PushDeliveryState.NoDeliveryApp
            "ChooseDeliveryApp" -> PushDeliveryState.ChooseDeliveryApp
            "WaitingForDelivery" -> {
                val dist = map["distributor"] as String
                val unans = map["unanswered"] as Boolean
                PushDeliveryState.WaitingForDelivery(dist, unans)
            }
            "InsecureAddress" -> PushDeliveryState.InsecureAddress
            "NotLinked" -> PushDeliveryState.NotLinked
            "Failed" -> {
                val reason = map["reason"] as String
                PushDeliveryState.Failed(reason)
            }
            else -> PushDeliveryState.Off
        }
}
