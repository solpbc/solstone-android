// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalBrowserCleartextPolicyRuntimeTest {

    @Test
    fun cleartextIsPermittedExclusivelyForLocalhostSubdomains() {
        val policy = NetworkSecurityPolicy.getInstance()
        val tokenHost = "0123456789abcdef0123456789abcdef.localhost"

        assertTrue(
            "Cleartext traffic must be permitted for $tokenHost under NetworkSecurityConfig",
            policy.isCleartextTrafficPermitted(tokenHost),
        )
        assertFalse(
            "Cleartext traffic must NOT be permitted for example.com",
            policy.isCleartextTrafficPermitted("example.com"),
        )
        assertFalse(
            "Cleartext traffic must NOT be permitted for 192.168.1.1",
            policy.isCleartextTrafficPermitted("192.168.1.1"),
        )
    }
}
