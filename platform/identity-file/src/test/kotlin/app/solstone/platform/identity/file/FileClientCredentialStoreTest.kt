// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.ClientCredential
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

class FileClientCredentialStoreTest {
    @Test
    fun roundTripsWrappedCredential() {
        val file = Files.createTempDirectory("credential-store-test").resolve("credential.pem").toFile()
        val protector = SpySecretProtector()
        val store = FileClientCredentialStore(file, protector)

        store.save(testCredential())

        assertEquals(testCredential(), store.load())
        assertEquals(1, protector.protectCalls)
        assertEquals(1, protector.unprotectCalls)
        assertTrue(file.readBytes().startsWithMarker())
    }

    @Test
    fun legacyCredentialLoadsThenSavesWrapped() {
        val file = Files.createTempDirectory("credential-store-test").resolve("credential.pem").toFile()
        file.writeText(credentialBlob(testCredential()))
        val protector = SpySecretProtector()
        val store = FileClientCredentialStore(file, protector)

        assertEquals(testCredential(), store.load())
        assertEquals(0, protector.unprotectCalls)

        store.save(testCredential())

        assertTrue(file.readBytes().startsWithMarker())
        assertEquals(testCredential(), store.load())
    }

    @Test
    fun markerDeterminismRoutesLegacyAndWrapped() {
        val file = Files.createTempDirectory("credential-store-test").resolve("credential.pem").toFile()
        val legacyProtector = SpySecretProtector()
        file.writeText(credentialBlob(testCredential()))

        assertEquals(testCredential(), FileClientCredentialStore(file, legacyProtector).load())
        assertEquals(0, legacyProtector.unprotectCalls)

        val wrappedProtector = SpySecretProtector()
        FileClientCredentialStore(file, wrappedProtector).save(testCredential())
        assertEquals(testCredential(), FileClientCredentialStore(file, wrappedProtector).load())
        assertEquals(1, wrappedProtector.unprotectCalls)
    }

    @Test
    fun savedFileIsOwnerOnly() {
        val file = Files.createTempDirectory("credential-store-test").resolve("credential.pem").toFile()

        FileClientCredentialStore(file, SpySecretProtector()).save(testCredential())

        assertEquals(setOf(OWNER_READ, OWNER_WRITE), Files.getPosixFilePermissions(file.toPath()))
    }

    @Test
    fun unwrapFailureReturnsNullAndLeavesFileLoadable() {
        val file = Files.createTempDirectory("credential-store-test").resolve("credential.pem").toFile()
        FileClientCredentialStore(file, SpySecretProtector()).save(testCredential())
        val original = file.readBytes()
        val logs = mutableListOf<String>()

        assertNull(FileClientCredentialStore(file, SpySecretProtector(failUnprotect = true), logs::add).load())

        assertTrue(file.exists())
        assertContentEquals(original, file.readBytes())
        assertEquals(testCredential(), FileClientCredentialStore(file, SpySecretProtector()).load())
        assertTrue(logs.single().contains("credential unwrap failed"))
        assertFalse(logs.single().contains(PRIVATE_KEY_PEM))
    }

    @Test
    fun malformedWrappedCredentialReturnsNull() {
        val file = Files.createTempDirectory("credential-store-test").resolve("credential.pem").toFile()
        FileClientCredentialStore(file, SpySecretProtector()).save(testCredential())

        val store = FileClientCredentialStore(
            file,
            SpySecretProtector(unprotectTransform = { "-----BEGIN PRIVATE KEY-----\nAAA\n".toByteArray() }),
        )

        assertNull(store.load())
    }

    @Test
    fun saveFailureLeavesExistingFileUntouched() {
        val file = Files.createTempDirectory("credential-store-test").resolve("credential.pem").toFile()
        val existing = credentialBlob(testCredential()).toByteArray()
        file.writeBytes(existing)

        assertFailsWith<RuntimeException> {
            FileClientCredentialStore(file, SpySecretProtector(failProtect = true)).save(testCredential())
        }

        assertContentEquals(existing, file.readBytes())
    }

    private fun testCredential(): ClientCredential = ClientCredential(
        privateKeyPem = PRIVATE_KEY_PEM,
        clientCertPem = CLIENT_CERT_PEM,
        caChainPem = listOf(CA_CERT_PEM),
    )

    private fun credentialBlob(credential: ClientCredential): String =
        credential.privateKeyPem + credential.clientCertPem + credential.caChainPem.joinToString("")

    private companion object {
        const val PRIVATE_KEY_PEM = "-----BEGIN PRIVATE KEY-----\nAAA\n-----END PRIVATE KEY-----\n"
        const val CLIENT_CERT_PEM = "-----BEGIN CERTIFICATE-----\nBBB\n-----END CERTIFICATE-----\n"
        const val CA_CERT_PEM = "-----BEGIN CERTIFICATE-----\nCCC\n-----END CERTIFICATE-----\n"
    }
}
