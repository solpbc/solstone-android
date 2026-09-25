// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.push

import app.solstone.core.identity.PairingGeneration
import app.solstone.core.pl.toJson
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushRegistrationFileTest {

    @Test
    fun identityEqualityHashCodeAndToStringRespectDistributorInstalledAt() {
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        val vapid1 = byteArrayOf(0x04, 0x01, 0x02)
        val vapid2 = byteArrayOf(0x04, 0x01, 0x02)
        val val1: Long? = java.lang.Long.valueOf(1_700_000_000_123L)
        val val2: Long? = java.lang.Long.valueOf(1_700_000_000_123L)

        val id1 = PushRegistrationIdentity(gen, vapid1, "app.distributor.primary", val1)
        val id2 = PushRegistrationIdentity(gen, vapid2, "app.distributor.primary", val2)
        val idDifferentTime = PushRegistrationIdentity(gen, vapid1, "app.distributor.primary", 1_700_000_000_999L)
        val idNull1 = PushRegistrationIdentity(gen, vapid1, "app.distributor.primary", null)
        val idNull2 = PushRegistrationIdentity(gen, vapid2, "app.distributor.primary", null)

        assertEquals(id1, id2)
        assertEquals(id1.hashCode(), id2.hashCode())

        assertNotEquals(id1, idDifferentTime)
        assertNotEquals(id1, idNull1)
        assertEquals(idNull1, idNull2)
        assertEquals(idNull1.hashCode(), idNull2.hashCode())

        assertFalse(id1.toString().contains("1700000000123"))
        assertEquals("PushRegistrationIdentity(generation=$gen, distributorPackage=app.distributor.primary)", id1.toString())
    }

    @Test
    fun decodeV1DocumentWithoutDistributorInstalledAtDefaultsToNullAndPreservesFields() {
        val tempDir = Files.createTempDirectory("file-v1-legacy").toFile()
        val file = File(tempDir, PushRegistrationFile.FILE_NAME)
        val vapidBytes = byteArrayOf(0x04, 0x10, 0x20, 0x30)
        val vapidB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(vapidBytes)

        val doc = mapOf(
            "v" to 1,
            "lastRegistered" to mapOf(
                "instanceId" to "inst-1",
                "clientCertFingerprint" to "sha256:cert1",
                "vapid" to vapidB64,
                "distributor" to "app.distributor.primary",
            ),
            "attempt" to mapOf(
                "identity" to mapOf(
                    "instanceId" to "inst-1",
                    "clientCertFingerprint" to "sha256:cert1",
                    "vapid" to vapidB64,
                    "distributor" to "app.distributor.primary",
                ),
                "startMillis" to 123456789L,
                "answered" to true,
                "unanswered" to false,
                "failure" to "some_failure",
            ),
            "ownerPick" to "app.distributor.primary",
            "endpoint" to mapOf(
                "url" to "https://push.example/ep1",
                "p256dh" to "p256dh-key",
                "auth" to "auth-token",
                "identity" to mapOf(
                    "instanceId" to "inst-1",
                    "clientCertFingerprint" to "sha256:cert1",
                    "vapid" to vapidB64,
                    "distributor" to "app.distributor.primary",
                ),
                "posted" to true,
            ),
            "unregisteredAt" to null,
            "pendingDeletes" to listOf(
                mapOf(
                    "url" to "https://push.example/old",
                    "instanceId" to "inst-1",
                    "clientCertFingerprint" to "sha256:cert1",
                    "failures" to 2,
                ),
            ),
            "delivery" to mapOf("kind" to "Ready"),
        )

        file.writeText(toJson(doc))

        val state = PushRegistrationFile.read(file) {}
        assertNotNull(state.lastRegistered)
        assertNull(state.lastRegistered.distributorInstalledAt)
        assertEquals("inst-1", state.lastRegistered.generation.instanceId)
        assertEquals("sha256:cert1", state.lastRegistered.generation.clientCertFingerprint)
        assertEquals("app.distributor.primary", state.lastRegistered.distributorPackage)
        assertContentEquals(vapidBytes, state.lastRegistered.vapidKey)

        assertNotNull(state.attempt)
        assertNull(state.attempt.identity.distributorInstalledAt)
        assertEquals(123456789L, state.attempt.startMillis)
        assertTrue(state.attempt.answered)
        assertFalse(state.attempt.unanswered)
        assertEquals("some_failure", state.attempt.failure)

        assertNotNull(state.endpoint)
        assertNotNull(state.endpoint.identity)
        assertNull(state.endpoint.identity.distributorInstalledAt)
        assertEquals("https://push.example/ep1", state.endpoint.url)
        assertEquals("p256dh-key", state.endpoint.p256dh)
        assertEquals("auth-token", state.endpoint.auth)
        assertTrue(state.endpoint.posted)

        assertEquals(1, state.pendingDeletes.size)
        val delete = state.pendingDeletes.single()
        assertEquals("https://push.example/old", delete.url)
        assertEquals("inst-1", delete.generation.instanceId)
        assertEquals("sha256:cert1", delete.generation.clientCertFingerprint)
        assertEquals(2, delete.failures)
    }

    @Test
    fun writeAndReadRoundTripsDistributorInstalledAt() {
        val tempDir = Files.createTempDirectory("file-roundtrip").toFile()
        val file = File(tempDir, PushRegistrationFile.FILE_NAME)
        val gen = PairingGeneration("inst-1", "sha256:cert1")
        val vapid = byteArrayOf(0x04, 0x42, 0x43)
        val installedAt = 1_700_000_000_123L
        val identity = PushRegistrationIdentity(gen, vapid, "app.distributor.primary", installedAt)

        val state = PushRegistrationState(
            lastRegistered = identity,
            attempt = PushRegistrationAttempt(identity, 5000L, answered = false, unanswered = false, failure = null),
            ownerPick = null,
            endpoint = PushRegistrationEndpoint("https://push.example/ep1", "key", "auth", identity, posted = true),
            unregisteredAt = null,
            pendingDeletes = emptyList(),
            delivery = PushDeliveryState.Ready,
        )

        PushRegistrationFile.write(file, state) {}
        val fileText = file.readText()
        assertTrue(fileText.contains("distributorInstalledAt"))
        assertTrue(fileText.contains("1700000000123"))

        val readState = PushRegistrationFile.read(file) {}
        assertEquals(installedAt, readState.lastRegistered?.distributorInstalledAt)
        assertEquals(installedAt, readState.attempt?.identity?.distributorInstalledAt)
        assertEquals(installedAt, readState.endpoint?.identity?.distributorInstalledAt)
    }

    @Test
    fun decodeInvalidDistributorInstalledAtValuesFallBackToNullAndPreserveDocument() {
        val badValues = listOf(
            "nope",
            BigDecimal("1.5"),
            BigDecimal("9223372036854775808"),
        )

        for (badVal in badValues) {
            val tempDir = Files.createTempDirectory("file-bad-val").toFile()
            val file = File(tempDir, PushRegistrationFile.FILE_NAME)
            val vapidBytes = byteArrayOf(0x04, 0x10, 0x20, 0x30)
            val vapidB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(vapidBytes)

            fun makeIdentityMap() = mapOf(
                "instanceId" to "inst-1",
                "clientCertFingerprint" to "sha256:cert1",
                "vapid" to vapidB64,
                "distributor" to "app.distributor.primary",
                "distributorInstalledAt" to badVal,
            )

            val doc = mapOf(
                "v" to 1,
                "lastRegistered" to makeIdentityMap(),
                "attempt" to mapOf(
                    "identity" to makeIdentityMap(),
                    "startMillis" to 1000L,
                    "answered" to false,
                    "unanswered" to false,
                    "failure" to null,
                ),
                "ownerPick" to null,
                "endpoint" to mapOf(
                    "url" to "https://push.example/ep1",
                    "p256dh" to "key",
                    "auth" to "auth",
                    "identity" to makeIdentityMap(),
                    "posted" to true,
                ),
                "unregisteredAt" to null,
                "pendingDeletes" to listOf(
                    mapOf(
                        "url" to "https://push.example/del",
                        "instanceId" to "inst-1",
                        "clientCertFingerprint" to "sha256:cert1",
                        "failures" to 0,
                    ),
                ),
                "delivery" to mapOf("kind" to "Ready"),
            )

            file.writeText(toJson(doc))

            val readState = PushRegistrationFile.read(file) {}
            assertNotNull(readState.endpoint)
            assertEquals("https://push.example/ep1", readState.endpoint.url)
            assertNotNull(readState.attempt)
            assertEquals(1, readState.pendingDeletes.size)
            assertEquals("https://push.example/del", readState.pendingDeletes.single().url)

            assertNull(readState.lastRegistered?.distributorInstalledAt)
            assertNull(readState.attempt.identity.distributorInstalledAt)
            assertNull(readState.endpoint.identity?.distributorInstalledAt)
        }
    }
}
