// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import java.net.URLEncoder

internal fun supportReportUrl(
    version: String,
    build: String,
    osVersion: String?,
    state: String,
): String {
    val fields = buildList {
        add("report" to "v1")
        add("app" to "solstone for android")
        version.takeIf(String::isNotEmpty)?.let { add("version" to it.take(120)) }
        build.takeIf(String::isNotEmpty)?.let { add("build" to it.take(120)) }
        add("os" to "android")
        osVersion?.takeIf(String::isNotEmpty)?.let { add("os_version" to it.take(120)) }
        state.takeIf(String::isNotEmpty)?.let { add("state" to it.take(500)) }
    }
    return "$SUPPORT_SITE_URL/#" + fields.joinToString("&") { (key, value) ->
        "${formEncode(key)}=${formEncode(value)}"
    }
}

internal fun supportState(status: PhoneDefaultDetailStatus): String = when (status) {
    PhoneDefaultDetailStatus.Loading -> "loading"
    PhoneDefaultDetailStatus.Failed -> "status unavailable"
    PhoneDefaultDetailStatus.Unpaired -> "not paired"
    is PhoneDefaultDetailStatus.Paired -> statusPillText(status.snapshot.status)
}

private fun formEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
