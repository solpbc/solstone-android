// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

/**
 * Process-local monotonic session epoch.
 */
data class BrowserEpoch(val id: Long) {
    override fun toString(): String = "BrowserEpoch($id)"
}

/**
 * Typed classification taxonomy for terminal browser states.
 * Redacted: No URLs, origins, tokens, IPs, ports, certs, or nested exception strings.
 */
enum class BrowserTerminalClass {
    BIND_FAILURE,
    SESSION_CARRIER_LOSS,
    TRUST_REFUSAL,
    IDENTITY_AUTH_REFUSAL,
    UNPAIRED_FORGOTTEN,
    PAIRING_REPLACED,
    PAIRING_UNCERTAIN,
    EXPLICIT_STOP,
    TERMINAL_UPSTREAM_PROTOCOL,
}

data class BrowserTerminalReason(
    val classification: BrowserTerminalClass,
    val code: Int = 0,
) {
    override fun toString(): String = "BrowserTerminalReason($classification, code=$code)"
}

/**
 * Public lifecycle states of a JournalBrowserSession.
 */
sealed interface JournalBrowserLifecycle {
    val epoch: BrowserEpoch

    data class Starting(override val epoch: BrowserEpoch) : JournalBrowserLifecycle {
        override fun toString(): String = "JournalBrowserLifecycle.Starting(epoch=$epoch)"
    }

    data class Live(override val epoch: BrowserEpoch) : JournalBrowserLifecycle {
        override fun toString(): String = "JournalBrowserLifecycle.Live(epoch=$epoch)"
    }

    data class Terminal(
        override val epoch: BrowserEpoch,
        val reason: BrowserTerminalReason,
    ) : JournalBrowserLifecycle {
        override fun toString(): String = "JournalBrowserLifecycle.Terminal(epoch=$epoch, reason=$reason)"
    }
}

/**
 * Listener for JournalBrowserSession lifecycle state changes.
 */
fun interface JournalBrowserLifecycleListener {
    fun onStateChanged(state: JournalBrowserLifecycle)
}
