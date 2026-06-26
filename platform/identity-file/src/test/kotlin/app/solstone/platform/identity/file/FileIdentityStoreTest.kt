// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileIdentityStoreTest {
    @Test
    fun roundTripsRelayDeviceToken() {
        val file = Files.createTempDirectory("identity-store-test").resolve("identity.tsv").toFile()
        val store = FileIdentityStore(file)
        val home = PairedHome(
            instanceId = "home",
            homeLabel = "Home",
            relayOrigin = "https://link.solstone.app",
            caChainFingerprint = "sha256:ca",
            clientCertFingerprint = "sha256:client",
            observerHandle = "obs",
            deviceToken = "device-token",
            state = IdentityState.PAIRED,
        )

        store.save(home)

        assertEquals(home, store.load())
    }

    @Test
    fun oldRecordsWithoutDeviceTokenStillLoad() {
        val file = Files.createTempDirectory("identity-store-test").resolve("identity.tsv").toFile()
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

        assertNull(FileIdentityStore(file).load()?.deviceToken)
    }
}
