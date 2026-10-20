// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.pl.parseJson
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class MobileImportContractAdoptionTest {
    @Test
    fun pinnedRevisionResourcesAreBound() {
        val adoption = json("mobile-import-v1/adoption.json")
        assertEquals("d4405f11b0748b067caa273be6a9d8a317757ca5", adoption["authority_commit"])
        assertEquals("app.solstone.core.observer.MobileImportClient", adoption["owner"])

        val files = adoption["files"] as List<*>
        files.forEach { raw ->
            val entry = raw as Map<*, *>
            val path = entry["path"] as String
            val resourceName = path.substringAfterLast('/')
            val resourcePath = "mobile-import-v1/$resourceName"
            assertEquals(entry["sha256"], sha256(bytes(resourcePath)), path)
        }
    }

    private fun json(path: String): Map<*, *> = parseJson(text(path)) as Map<*, *>
    private fun text(path: String): String = bytes(path).toString(Charsets.UTF_8)
    private fun bytes(path: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path)).use { it.readBytes() }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
