// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import java.net.URI

internal class JournalWebPolicy(origin: String) {
    private val authority = parse(origin) ?: error("invalid journal browser origin")

    fun allows(url: String): Boolean = parse(url) == authority

    fun decide(url: String, isMainFrame: Boolean): JournalWebNavigation {
        if (allows(url)) {
            return JournalWebNavigation(
                consume = false,
                openUrl = null,
                notifyIfGesture = false,
            )
        }
        if (!isMainFrame) {
            return JournalWebNavigation(
                consume = true,
                openUrl = null,
                notifyIfGesture = false,
            )
        }
        if (isHandoffUrl(url)) {
            return JournalWebNavigation(
                consume = true,
                openUrl = url,
                notifyIfGesture = false,
            )
        }
        return JournalWebNavigation(
            consume = true,
            openUrl = null,
            notifyIfGesture = true,
        )
    }

    private fun isHandoffUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.userInfo != null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        val originHost = authority.host
        return host != originHost &&
            !host.startsWith("$originHost.") &&
            !host.endsWith(".$originHost")
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

internal data class JournalWebNavigation(
    val consume: Boolean,
    val openUrl: String?,
    val notifyIfGesture: Boolean,
)

internal fun applyJournalWebNavigation(
    decision: JournalWebNavigation,
    hasGesture: Boolean,
    open: (String) -> Unit,
    notify: () -> Unit,
): Boolean {
    val openUrl = decision.openUrl
    if (openUrl != null) {
        try {
            open(openUrl)
        } catch (_: Throwable) {
            notify()
        }
    } else if (decision.notifyIfGesture && hasGesture) {
        notify()
    }
    return decision.consume
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
