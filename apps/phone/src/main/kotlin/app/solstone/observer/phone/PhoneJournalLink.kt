// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import java.net.URI

internal sealed interface PhoneJournalLink {
    data object Other : PhoneJournalLink
    data object Invalid : PhoneJournalLink
    class Open(val path: String) : PhoneJournalLink {
        override fun toString(): String = "Open(<redacted>)"
    }
}

private val journalLinkToken = Regex("[A-Za-z0-9_-]{1,512}(~[0-9]{6})?")

internal fun phoneJournalLink(raw: String?): PhoneJournalLink {
    if (raw == null) return PhoneJournalLink.Other
    val uri = try {
        URI(raw)
    } catch (_: Exception) {
        return if (raw.startsWith("https://go.solstone.app/j")) PhoneJournalLink.Invalid else PhoneJournalLink.Other
    }
    if (uri.rawPath != "/j") return PhoneJournalLink.Other
    if (uri.scheme != "https" || uri.rawAuthority != "go.solstone.app" || uri.rawQuery != null) {
        return PhoneJournalLink.Invalid
    }
    // Read the raw fragment. Decoding here would accept a different wire grammar.
    val token = uri.rawFragment ?: return PhoneJournalLink.Invalid
    if (!journalLinkToken.matches(token)) return PhoneJournalLink.Invalid
    return PhoneJournalLink.Open("/app/open/$token")
}
