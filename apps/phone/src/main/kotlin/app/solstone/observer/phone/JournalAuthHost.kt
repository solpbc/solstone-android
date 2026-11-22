// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.pl.parseJson
import java.math.BigDecimal
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Frozen v2 OAuth brokerage contract shipped beside the journal web host adapter. */
internal data class AuthHostContract(
    val json: String,
    val capability: String,
    val prepare: String,
    val prepared: String,
    val authorize: String,
    val cancel: String,
    val result: String,
    val unsupported: String,
)

internal fun parseAuthHostContract(bytes: ByteArray): AuthHostContract? {
    val text = bytes.toString(Charsets.UTF_8)
    val root = runCatching { parseJson(text) }.getOrNull() as? Map<*, *> ?: return null
    val version = root["version"] as? BigDecimal ?: return null
    if (version.compareTo(BigDecimal(2)) != 0) return null
    fun name(key: String) = (root[key] as? String)?.takeIf { it.isNotEmpty() }
    return AuthHostContract(
        json = text.trim(),
        capability = name("capability") ?: return null,
        prepare = name("prepare") ?: return null,
        prepared = name("prepared") ?: return null,
        authorize = name("authorize") ?: return null,
        cancel = name("cancel") ?: return null,
        result = name("result") ?: return null,
        unsupported = name("unsupported") ?: return null,
    )
}

/** Strict allowlist of OAuth custom-scheme redirect targets accepted on Android. */
internal enum class AuthCustomSchemeTarget(
    val redirectUri: String,
    val scheme: String,
    val expectedHost: String?,
    val expectedPath: String,
) {
    SOLSTONE_OAUTH("solstone://oauth/callback", "solstone", "oauth", "/callback"),
    APP_SOLSTONE("app.solstone:/callback", "app.solstone", null, "/callback"),
    ;

    companion object {
        fun fromRedirectUri(uri: String): AuthCustomSchemeTarget? =
            entries.firstOrNull { it.redirectUri == uri }
    }
}

internal sealed interface AuthPreference {
    data class Loopback(val host: String, val path: String, val port: Int) : AuthPreference
    data class CustomScheme(val uri: String) : AuthPreference
    data class Other(val mode: String) : AuthPreference
}

internal sealed interface AuthParseResult<out T> {
    data class Ok<T>(val value: T) : AuthParseResult<T>
    data class Err(val reason: String) : AuthParseResult<Nothing>
}

internal object JournalAuthValidation {
    const val MAX_ID_BYTES = 128
    const val MAX_PREFERENCES = 16
    const val MAX_PATH_BYTES = 256
    const val MAX_AUTHORIZE_URL_BYTES = 8_192
    const val MAX_STATE_BYTES = 1_024
    const val MIN_TIMEOUT_MS = 1_000L
    const val MAX_TIMEOUT_MS = 600_000L
    const val DEFAULT_TIMEOUT_MS = 600_000L

    private val random = SecureRandom()

    /**
     * Recognises an OAuth custom-scheme callback URI so [PhoneShellActivity] can route it to the
     * active [JournalAuthHost] before journal-link or pairing-link handling, never mutating pairing
     * state or dismissing an open journal sheet.
     */
    fun isOAuthCallbackUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrEmpty()) return false
        val lower = rawUrl.lowercase()
        if (lower.startsWith("app.solstone:")) return true
        if (lower.startsWith("solstone://oauth")) {
            val next = lower.getOrNull("solstone://oauth".length)
            return next == null || next == '/' || next == '?' || next == '#'
        }
        return false
    }

    fun validId(raw: Any?): String? {
        val id = raw as? String ?: return null
        val bytes = id.toByteArray(Charsets.UTF_8)
        return if (bytes.isNotEmpty() && bytes.size <= MAX_ID_BYTES) id else null
    }

    fun isValidLoopbackPath(path: String): Boolean {
        val bytes = path.toByteArray(Charsets.UTF_8)
        if (!path.startsWith("/") || bytes.isEmpty() || bytes.size > MAX_PATH_BYTES) return false
        if ('?' in path || '#' in path || ".." in path) return false
        return bytes.all { (it.toInt() and 0xFF) in 0x21..0x7E }
    }

    fun parsePreferences(raw: Any?): AuthParseResult<List<AuthPreference>> {
        val list = raw as? List<*> ?: return AuthParseResult.Err("invalid_preferences")
        if (list.isEmpty() || list.size > MAX_PREFERENCES) {
            return AuthParseResult.Err("invalid_preferences")
        }
        val parsed = ArrayList<AuthPreference>(list.size)
        for (item in list) {
            val obj = item as? Map<*, *> ?: return AuthParseResult.Err("invalid_preferences")
            val mode = (obj["mode"] as? String)?.takeIf { it.isNotEmpty() }
                ?: return AuthParseResult.Err("invalid_preferences")
            when (mode) {
                "loopback" -> {
                    val host = obj["host"] as? String ?: return AuthParseResult.Err("invalid_loopback_host")
                    if (host != "127.0.0.1" && host != "localhost") {
                        return AuthParseResult.Err("invalid_loopback_host")
                    }
                    val path = obj["path"] as? String ?: return AuthParseResult.Err("invalid_loopback_path")
                    if (!isValidLoopbackPath(path)) {
                        return AuthParseResult.Err("invalid_loopback_path")
                    }
                    val port = when (val rawPort = obj["port"]) {
                        null -> 0
                        is BigDecimal -> {
                            val intVal = runCatching { rawPort.intValueExact() }.getOrNull()
                                ?: return AuthParseResult.Err("invalid_loopback_port")
                            when {
                                intVal == 0 -> 0
                                intVal in 1024..65535 -> intVal
                                else -> return AuthParseResult.Err("invalid_loopback_port")
                            }
                        }
                        else -> return AuthParseResult.Err("invalid_loopback_port")
                    }
                    parsed += AuthPreference.Loopback(host, path, port)
                }
                "custom_scheme" -> {
                    val uri = (obj["uri"] as? String)?.takeIf { it.isNotEmpty() }
                        ?: return AuthParseResult.Err("invalid_custom_scheme")
                    parsed += AuthPreference.CustomScheme(uri)
                }
                else -> parsed += AuthPreference.Other(mode)
            }
        }
        return AuthParseResult.Ok(parsed)
    }

    /**
     * Strict validation of `authorize_url`: `https://` only, <= 8192 UTF-8 bytes, no userinfo (`@`),
     * no whitespace or ASCII control characters, non-empty host.
     */
    fun validateAuthorizeUrl(raw: Any?): String? {
        val text = raw as? String ?: return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_AUTHORIZE_URL_BYTES) return null
        for (ch in text) {
            if (ch.code <= 0x20 || ch.code == 0x7F || ch.isWhitespace()) return null
        }
        if (text.length < 8 || !text.substring(0, 8).equals("https://", ignoreCase = true)) return null
        val afterScheme = text.substring(8)
        val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let {
            if (it < 0) afterScheme.length else it
        }
        val authority = afterScheme.substring(0, authorityEnd)
        if (authority.isEmpty() || '@' in authority) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        if (uri.rawUserInfo != null) return null
        if (uri.host.isNullOrEmpty()) return null
        return text
    }

    fun validateExpectedState(raw: Any?): String? {
        val state = raw as? String ?: return null
        val bytes = state.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_STATE_BYTES) return null
        if (!bytes.all { (it.toInt() and 0xFF) in 0x21..0x7E }) return null
        return state
    }

    fun parseTimeoutMillis(raw: Any?): AuthParseResult<Long> = when (raw) {
        null -> AuthParseResult.Ok(DEFAULT_TIMEOUT_MS)
        is BigDecimal -> {
            val seconds = raw.toDouble()
            if (!seconds.isFinite()) {
                AuthParseResult.Err("invalid_timeout")
            } else {
                val ms = (seconds * 1_000.0).toLong().coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
                AuthParseResult.Ok(ms)
            }
        }
        else -> AuthParseResult.Err("invalid_timeout")
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        val ab = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        if (ab.size != bb.size) return false
        var diff = 0
        for (i in ab.indices) {
            diff = diff or ((ab[i].toInt() and 0xFF) xor (bb[i].toInt() and 0xFF))
        }
        return diff == 0
    }

    fun percentDecode(input: String): String? {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val out = ByteArray(bytes.size)
        var outLen = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when (b) {
                0x2B -> {
                    out[outLen++] = 0x20
                    i++
                }
                0x25 -> {
                    if (i + 2 >= bytes.size) return null
                    val hi = hexNibble(bytes[i + 1].toInt() and 0xFF) ?: return null
                    val lo = hexNibble(bytes[i + 2].toInt() and 0xFF) ?: return null
                    out[outLen++] = ((hi shl 4) or lo).toByte()
                    i += 3
                }
                else -> {
                    out[outLen++] = bytes[i]
                    i++
                }
            }
        }
        return runCatching {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(out, 0, outLen)).toString()
        }.getOrNull()
    }

    private fun hexNibble(b: Int): Int? = when (b) {
        in 0x30..0x39 -> b - 0x30
        in 0x41..0x46 -> b - 0x41 + 10
        in 0x61..0x66 -> b - 0x61 + 10
        else -> null
    }

    fun parseQueryRefusingDuplicates(query: String): Map<String, String>? {
        val params = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val rawKey = if (eq >= 0) pair.substring(0, eq) else pair
            val rawVal = if (eq >= 0) pair.substring(eq + 1) else ""
            val key = percentDecode(rawKey) ?: return null
            val value = percentDecode(rawVal) ?: return null
            if (params.containsKey(key)) return null
            params[key] = value
        }
        return params
    }

    /**
     * Validates an incoming Android custom-scheme callback URL against exact scheme + host + path,
     * duplicate-query-key prohibition, constant-time `state` match, and presence of `code` or
     * `error`. Never logs the URL.
     */
    fun validateCustomSchemeCallbackUrl(
        rawUrl: String,
        target: AuthCustomSchemeTarget,
        expectedState: String,
    ): String? {
        if (rawUrl.isEmpty()) return null
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        if (!target.scheme.equals(uri.scheme, ignoreCase = true)) return null
        if (uri.rawUserInfo != null || uri.port != -1) return null

        when (target) {
            AuthCustomSchemeTarget.SOLSTONE_OAUTH -> {
                if (!"oauth".equals(uri.host, ignoreCase = true)) return null
                if (uri.rawPath != target.expectedPath) return null
            }
            AuthCustomSchemeTarget.APP_SOLSTONE -> {
                // Reject authority/double-slash smuggling such as `app.solstone://callback?...`
                // or `app.solstone:///callback?...`.
                if (rawUrl.lowercase().startsWith("app.solstone://")) return null
                if (!uri.host.isNullOrEmpty() || uri.rawAuthority != null) return null
                if (uri.rawPath != target.expectedPath) return null
            }
        }

        val rawQuery = uri.rawQuery?.takeIf { it.isNotEmpty() } ?: return null
        val params = parseQueryRefusingDuplicates(rawQuery) ?: return null
        val state = params["state"] ?: return null
        if (!constantTimeEquals(state, expectedState)) return null
        if (!params.containsKey("code") && !params.containsKey("error")) return null
        return rawUrl
    }

    fun randomSessionId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val sb = StringBuilder(32)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}

/** Injectable timer handle so timeout behavior is unit-testable without sleeping. */
internal fun interface JournalAuthTimerScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

internal object SystemJournalAuthTimerScheduler : JournalAuthTimerScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "journal-auth-timer").apply { isDaemon = true }
    }

    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val future: ScheduledFuture<*> = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        return { future.cancel(false) }
    }
}

/**
 * In-process handoff from [PhoneShellActivity] (`onNewIntent` and `AuthTabIntent` result callback)
 * to the currently open [JournalAuthHost]. Never stores or logs callback URLs.
 */
internal object JournalAuthCallbackBus {
    private val lock = Any()
    private var activeHost: JournalAuthHost? = null

    fun register(host: JournalAuthHost) {
        synchronized(lock) { activeHost = host }
    }

    fun unregister(host: JournalAuthHost) {
        synchronized(lock) {
            if (activeHost === host) {
                activeHost = null
            }
        }
    }

    fun deliver(rawUrl: String): Boolean {
        val host = synchronized(lock) { activeHost } ?: return false
        return host.onCallbackUrl(rawUrl)
    }

    fun notifyAuthTabClosed(refusedReason: String?) {
        val host = synchronized(lock) { activeHost } ?: return
        host.onAuthTabClosed(refusedReason)
    }
}

/**
 * Manages the single active OAuth custom-scheme brokerage session for an Android journal WebView.
 * Holds zero tokens or PKCE verifiers and never logs `callback_url`.
 */
internal class JournalAuthHost(
    val contract: AuthHostContract,
    private val openBrowser: (authorizeUrl: String, callbackScheme: String) -> Boolean,
    private val timerScheduler: JournalAuthTimerScheduler = SystemJournalAuthTimerScheduler,
    private val idleTimeoutMs: Long = JournalAuthValidation.DEFAULT_TIMEOUT_MS,
) {
    private data class ActiveSession(
        val sessionId: String,
        val target: AuthCustomSchemeTarget,
        var authorizingId: String? = null,
        var expectedState: String? = null,
        var reply: ((Map<String, Any?>) -> Unit)? = null,
        var cancelTimer: (() -> Unit)? = null,
    )

    private val lock = Any()
    private var active: ActiveSession? = null

    fun handles(type: Any?): Boolean =
        type == contract.prepare || type == contract.authorize || type == contract.cancel

    fun handle(message: Map<*, *>, answer: (Map<String, Any?>) -> Unit) {
        when (message["type"]) {
            contract.prepare -> prepare(message, answer)
            contract.authorize -> authorize(message, answer)
            contract.cancel -> cancel(message, answer)
            else -> answer(mapOf("type" to contract.unsupported, "id" to message["id"]))
        }
    }

    /**
     * Tears down any active session, settling any pending `auth.authorize` Promise with
     * `outcome: "cancelled"`.
     */
    fun tearDown() {
        settleActive(outcome = "cancelled", reason = null)
    }

    /**
     * Attempts to complete an active authorizing session from a custom-scheme callback URL.
     * Returns `true` only when the URL matches the exact prepared target, has no duplicate query
     * keys, passes constant-time state comparison, and carries `code` or `error`. A mismatched or
     * stray URL returns `false` and leaves the session open and unconsumed.
     */
    fun onCallbackUrl(rawUrl: String): Boolean {
        val matched: Triple<String, String, (Map<String, Any?>) -> Unit> = synchronized(lock) {
            val current = active ?: return false
            val authId = current.authorizingId ?: return false
            val expectedState = current.expectedState ?: return false
            val validated = JournalAuthValidation.validateCustomSchemeCallbackUrl(
                rawUrl = rawUrl,
                target = current.target,
                expectedState = expectedState,
            ) ?: return false
            active = null
            current.cancelTimer?.invoke()
            current.cancelTimer = null
            val reply = current.reply ?: return false
            current.reply = null
            Triple(authId, validated, reply)
        }
        matched.third(
            linkedMapOf(
                "type" to contract.result,
                "id" to matched.first,
                "outcome" to "callback",
                "callback_url" to matched.second,
            ),
        )
        return true
    }

    /**
     * Called only when Chrome Auth Tab returns a terminal ActivityResult without a matching
     * callback (user dismissed Auth Tab, or Auth Tab returned a mismatched URI).
     */
    fun onAuthTabClosed(refusedReason: String?) {
        if (refusedReason == null) {
            settleActive(outcome = "cancelled", reason = null)
        } else {
            settleActive(outcome = "refused", reason = refusedReason)
        }
    }

    private fun prepare(message: Map<*, *>, answer: (Map<String, Any?>) -> Unit) {
        val id = JournalAuthValidation.validId(message["id"])
        if (id == null) {
            answer(mapOf("type" to contract.unsupported, "id" to ""))
            return
        }
        fun prepared(outcome: String, extra: Map<String, Any?> = emptyMap()) =
            answer(linkedMapOf<String, Any?>("type" to contract.prepared, "id" to id, "outcome" to outcome) + extra)

        val preferences = when (val parsed = JournalAuthValidation.parsePreferences(message["preferences"])) {
            is AuthParseResult.Ok -> parsed.value
            is AuthParseResult.Err -> {
                prepared("refused", mapOf("reason" to parsed.reason))
                return
            }
        }

        // Single active session: tear down any prior idle or in-flight session first so an earlier
        // `auth.authorize` settles cleanly as `cancelled`.
        settleActive(outcome = "cancelled", reason = null)

        for (pref in preferences) {
            if (pref !is AuthPreference.CustomScheme) continue
            val target = AuthCustomSchemeTarget.fromRedirectUri(pref.uri) ?: continue
            val sessionId = JournalAuthValidation.randomSessionId()
            val session = ActiveSession(sessionId = sessionId, target = target)
            synchronized(lock) {
                session.cancelTimer = timerScheduler.schedule(idleTimeoutMs) {
                    expireIfMatching(sessionId)
                }
                active = session
            }
            prepared(
                "ready",
                linkedMapOf(
                    "session_id" to sessionId,
                    "mode" to "custom_scheme",
                    "redirect_uri" to target.redirectUri,
                ),
            )
            return
        }

        prepared("no_compatible_mode")
    }

    private fun authorize(message: Map<*, *>, answer: (Map<String, Any?>) -> Unit) {
        val id = JournalAuthValidation.validId(message["id"])
        if (id == null) {
            answer(mapOf("type" to contract.unsupported, "id" to ""))
            return
        }
        fun result(outcome: String, extra: Map<String, Any?> = emptyMap()) =
            answer(linkedMapOf<String, Any?>("type" to contract.result, "id" to id, "outcome" to outcome) + extra)

        val sessionId = (message["session_id"] as? String)?.takeIf { it.isNotEmpty() }
        if (sessionId == null) {
            result("refused", mapOf("reason" to "invalid_session"))
            return
        }
        val authorizeUrl = JournalAuthValidation.validateAuthorizeUrl(message["authorize_url"])
        if (authorizeUrl == null) {
            result("refused", mapOf("reason" to "invalid_authorize_url"))
            return
        }
        val expectedState = JournalAuthValidation.validateExpectedState(message["expected_state"])
        if (expectedState == null) {
            result("refused", mapOf("reason" to "invalid_state"))
            return
        }
        val timeoutMs = when (val parsed = JournalAuthValidation.parseTimeoutMillis(message["timeout_seconds"])) {
            is AuthParseResult.Ok -> parsed.value
            is AuthParseResult.Err -> {
                result("refused", mapOf("reason" to parsed.reason))
                return
            }
        }

        val target: AuthCustomSchemeTarget = synchronized(lock) {
            val current = active
            if (current == null || current.sessionId != sessionId || current.authorizingId != null) {
                result("refused", mapOf("reason" to "invalid_session"))
                return
            }
            current.cancelTimer?.invoke()
            current.authorizingId = id
            current.expectedState = expectedState
            current.reply = answer
            current.cancelTimer = timerScheduler.schedule(timeoutMs) {
                expireIfMatching(sessionId)
            }
            current.target
        }

        val launched = runCatching { openBrowser(authorizeUrl, target.scheme) }.getOrDefault(false)
        if (!launched) {
            settleIfMatching(sessionId, outcome = "error", reason = "browser_launch_failed")
        }
    }

    private fun cancel(message: Map<*, *>, answer: (Map<String, Any?>) -> Unit) {
        val id = JournalAuthValidation.validId(message["id"])
        if (id == null) {
            answer(mapOf("type" to contract.unsupported, "id" to ""))
            return
        }
        val requestedSession = when (val raw = message["session_id"]) {
            null -> null
            is String -> raw.takeIf { it.isNotEmpty() }
            else -> {
                answer(linkedMapOf("type" to contract.result, "id" to id, "outcome" to "cancelled"))
                return
            }
        }
        if (requestedSession == null) {
            settleActive(outcome = "cancelled", reason = null)
        } else {
            settleIfMatching(requestedSession, outcome = "cancelled", reason = null)
        }
        answer(linkedMapOf("type" to contract.result, "id" to id, "outcome" to "cancelled"))
    }

    private fun expireIfMatching(sessionId: String) {
        settleIfMatching(sessionId, outcome = "timeout", reason = null)
    }

    private fun settleActive(outcome: String, reason: String?) {
        val settled: Pair<String, (Map<String, Any?>) -> Unit>? = synchronized(lock) {
            val current = active ?: return
            active = null
            current.cancelTimer?.invoke()
            current.cancelTimer = null
            val authId = current.authorizingId
            val reply = current.reply
            current.reply = null
            if (authId != null && reply != null) authId to reply else null
        }
        if (settled != null) {
            val body = linkedMapOf<String, Any?>(
                "type" to contract.result,
                "id" to settled.first,
                "outcome" to outcome,
            )
            if (reason != null) body["reason"] = reason
            settled.second(body)
        }
    }

    private fun settleIfMatching(sessionId: String, outcome: String, reason: String?) {
        val settled: Pair<String, (Map<String, Any?>) -> Unit>? = synchronized(lock) {
            val current = active
            if (current == null || current.sessionId != sessionId) return
            active = null
            current.cancelTimer?.invoke()
            current.cancelTimer = null
            val authId = current.authorizingId
            val reply = current.reply
            current.reply = null
            if (authId != null && reply != null) authId to reply else null
        }
        if (settled != null) {
            val body = linkedMapOf<String, Any?>(
                "type" to contract.result,
                "id" to settled.first,
                "outcome" to outcome,
            )
            if (reason != null) body["reason"] = reason
            settled.second(body)
        }
    }
}
