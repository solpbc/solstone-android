// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileIdentityStoreTest {
    @Test
    fun roundTripsRelayDeviceToken() {
        val file = Files.createTempDirectory("identity-store-test").resolve("identity.tsv").toFile()
        val protector = SpySecretProtector()
        val store = FileIdentityStore(file, protector)
        val home = PairedHome(
            instanceId = "home",
            homeLabel = "Home",
            relayOrigin = "https://link.solstone.app",
            caChainFingerprint = "sha256:ca",
            clientCertFingerprint = "sha256:client",
            observerHandle = "obs",
            deviceToken = "device-token",
            expiresAt = "2026-01-01T00:00:00Z",
            state = IdentityState.PAIRED,
        )

        store.save(home)

        assertEquals(home, store.load())
        assertEquals(1, protector.protectCalls)
        assertEquals(1, protector.unprotectCalls)
        assertTrue(file.readBytes().startsWithMarker())
    }

    @Test
    fun oldRecordsWithoutDeviceTokenStillLoad() {
        val file = Files.createTempDirectory("identity-store-test").resolve("identity.tsv").toFile()
        val protector = SpySecretProtector()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            instanceId	home
            homeLabel	Home
            caChainFingerprint	sha256:ca
            clientCertFingerprint	sha256:client
            observerHandle	obs
            state	PAIRED
            """.trimIndent() + "\n",
        )

        val loaded = FileIdentityStore(file, protector).load()
        assertNull(loaded?.deviceToken)
        assertNull(loaded?.expiresAt)
        assertEquals(0, protector.unprotectCalls)
    }

    @Test
    fun nullExpiresAtIsNotWritten() {
        val file = Files.createTempDirectory("identity-store-test").resolve("identity.tsv").toFile()
        val store = FileIdentityStore(file, SpySecretProtector())
        val home = PairedHome(
            instanceId = "home",
            homeLabel = "Home",
            relayOrigin = "https://link.solstone.app",
            caChainFingerprint = "sha256:ca",
            clientCertFingerprint = "sha256:client",
            observerHandle = "obs",
            deviceToken = "device-token",
            expiresAt = null,
            state = IdentityState.PAIRED,
        )

        store.save(home)

        assertEquals(home, store.load())
        val plaintext = file.readBytes().copyOfRange(WRAP_MARKER.size, file.readBytes().size).decodeToString()
        assertFalse(plaintext.contains("expiresAt\t"))
    }

    @Test
    fun markerDeterminismRoutesLegacyAndWrapped() {
        val file = Files.createTempDirectory("identity-store-test").resolve("identity.tsv").toFile()
        val legacyProtector = SpySecretProtector()
        file.writeText(identityBlob(testHome()))

        assertEquals(testHome(), FileIdentityStore(file, legacyProtector).load())
        assertEquals(0, legacyProtector.unprotectCalls)

        val wrappedProtector = SpySecretProtector()
        FileIdentityStore(file, wrappedProtector).save(testHome())
        assertEquals(testHome(), FileIdentityStore(file, wrappedProtector).load())
        assertEquals(1, wrappedProtector.unprotectCalls)
    }

    @Test
    fun savedFileIsOwnerOnly() {
        val file = Files.createTempDirectory("identity-store-test").resolve("identity.tsv").toFile()

        FileIdentityStore(file, SpySecretProtector()).save(testHome())

        assertEquals(setOf(OWNER_READ, OWNER_WRITE), Files.getPosixFilePermissions(file.toPath()))
    }

    @Test
    fun unwrapFailureReturnsNullAndLeavesFileLoadable() {
        val file = Files.createTempDirectory("identity-store-test").resolve("identity.tsv").toFile()
        val home = testHome(deviceToken = "secret-device-token")
        FileIdentityStore(file, SpySecretProtector()).save(home)
        val original = file.readBytes()
        val logs = mutableListOf<String>()

        assertNull(FileIdentityStore(file, SpySecretProtector(failUnprotect = true), logs::add).load())

        assertTrue(file.exists())
        assertContentEquals(original, file.readBytes())
        assertEquals(home, FileIdentityStore(file, SpySecretProtector()).load())
        assertTrue(logs.single().contains("identity blob malformed"))
        assertFalse(logs.single().contains(home.instanceId))
        assertFalse(logs.single().contains(home.deviceToken!!))
    }

    @Test
    fun malformedWrappedIdentityReturnsNull() {
        val file = Files.createTempDirectory("identity-store-test").resolve("identity.tsv").toFile()
        FileIdentityStore(file, SpySecretProtector()).save(testHome())

        val store = FileIdentityStore(
            file,
            SpySecretProtector(unprotectTransform = { "instanceId\thome\n".toByteArray() }),
        )

        assertNull(store.load())
    }

    @Test
    fun saveFailureLeavesExistingFileUntouched() {
        val file = Files.createTempDirectory("identity-store-test").resolve("identity.tsv").toFile()
        val existing = "legacy-existing".toByteArray()
        file.writeBytes(existing)

        assertFailsWith<RuntimeException> {
            FileIdentityStore(file, SpySecretProtector(failProtect = true)).save(testHome())
        }

        assertContentEquals(existing, file.readBytes())
    }

    private fun testHome(deviceToken: String? = "device-token"): PairedHome = PairedHome(
        instanceId = "home",
        homeLabel = "Home",
        relayOrigin = "https://link.solstone.app",
        caChainFingerprint = "sha256:ca",
        clientCertFingerprint = "sha256:client",
        observerHandle = "obs",
        deviceToken = deviceToken,
        expiresAt = "2026-01-01T00:00:00Z",
        state = IdentityState.PAIRED,
    )

    private fun identityBlob(home: PairedHome): String = buildList {
        add("instanceId\t${home.instanceId}")
        add("homeLabel\t${home.homeLabel}")
        home.relayOrigin?.let { add("relayOrigin\t$it") }
        home.deviceToken?.let { add("deviceToken\t$it") }
        home.expiresAt?.let { add("expiresAt\t$it") }
        add("caChainFingerprint\t${home.caChainFingerprint}")
        add("clientCertFingerprint\t${home.clientCertFingerprint}")
        home.observerHandle?.let { add("observerHandle\t$it") }
        add("state\t${home.state.name}")
    }.joinToString(separator = "\n", postfix = "\n")
}
