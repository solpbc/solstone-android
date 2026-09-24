// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PushKeyStorageDeviceTest {

    @Test
    fun pushKeyProtectorUsesIsolatedAliasAndKeyStore() {
        val pushAlias = "app.solstone.push.wrap.v1"
        val identityAlias = "app.solstone.identity.wrap.v1"

        val pushProtector = AndroidKeyStoreProtector(pushAlias)
        val identityProtector = AndroidKeyStoreProtector(identityAlias)

        val plaintext = "test-secret-payload-32-bytes-long!!".toByteArray(Charsets.UTF_8)

        val pushProtected = pushProtector.protect(plaintext)
        assertFalse(pushProtected.contentEquals(plaintext), "Ciphertext must not match plaintext")

        val pushUnprotected = pushProtector.unprotect(pushProtected)
        assertContentEquals(plaintext, pushUnprotected, "Push protector must correctly unprotect payload")

        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(ks.containsAlias(pushAlias), "KeyStore must contain push alias")

        // Isolation test: identity protector cannot unprotect bytes wrapped by push protector
        assertFailsWith<Exception> {
            identityProtector.unprotect(pushProtected)
        }
    }
}
