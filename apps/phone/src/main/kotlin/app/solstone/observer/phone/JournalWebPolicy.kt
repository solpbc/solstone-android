// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import java.net.URI

internal class JournalWebPolicy(origin: String) {
    private val authority = parse(origin) ?: error("invalid journal browser origin")

    fun allows(url: String): Boolean = parse(url) == authority

    fun ownerInitiatedForeign(url: String, isMainFrame: Boolean, hasGesture: Boolean): Boolean {
        if (!isMainFrame || !hasGesture || allows(url)) return false
        return runCatching { URI(url) }.getOrNull() != null
    }

    private fun parse(value: String): Authority? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = when {
            uri.port >= 0 -> uri.port
            scheme == "http" -> 80
            scheme == "https" -> 443
            else -> return null
        }
        if (uri.userInfo != null) return null
        return Authority(scheme, host, port)
    }

    private data class Authority(val scheme: String, val host: String, val port: Int)
}

internal data class JournalWebInsetPolicy(
    val applyNativeSystemInsets: Boolean,
    val applyNativeImeInsets: Boolean,
)

internal fun journalWebInsetPolicy(webViewVersionName: String?): JournalWebInsetPolicy {
    val milestone = webViewVersionName
        ?.substringBefore('.')
        ?.toIntOrNull()
        ?: 0
    return JournalWebInsetPolicy(
        applyNativeSystemInsets = milestone < 144,
        applyNativeImeInsets = milestone < 139,
    )
}
