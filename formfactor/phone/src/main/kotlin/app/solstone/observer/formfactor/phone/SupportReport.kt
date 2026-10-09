// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import java.net.URLEncoder

fun supportReportUrl(
    version: String?,
    build: String?,
    osVersion: String?,
    state: String,
    about: String,
): String = "$SUPPORT_SITE_URL/#${supportReportFields(version, build, osVersion, state, about)}"

fun supportReportFields(
    version: String?,
    build: String?,
    osVersion: String?,
    state: String,
    about: String,
    addresses: String? = null,
): String {
    val fields = buildList {
        add("report" to "v1")
        add("app" to "solstone for android")
        version?.trimStart { it == 'v' }?.takeIf(String::isNotBlank)?.let { add("version" to it.take(120)) }
        build?.takeIf(String::isNotBlank)?.let { add("build" to it.take(120)) }
        add("os" to "android")
        osVersion?.takeIf(String::isNotBlank)?.let { add("os_version" to it.take(120)) }
        state.takeIf(String::isNotEmpty)?.let { add("state" to it.take(500)) }
        add("about" to about)
        addresses?.takeIf(String::isNotBlank)?.let { add("addresses" to it) }
    }
    return fields.joinToString("&") { (key, value) ->
        "${formEncode(key)}=${formEncode(value)}"
    }
}

fun supportState(status: PhoneDefaultDetailStatus): String = when (status) {
    PhoneDefaultDetailStatus.Loading -> "loading"
    PhoneDefaultDetailStatus.Failed -> "status unavailable"
    PhoneDefaultDetailStatus.Unpaired -> "not paired"
    is PhoneDefaultDetailStatus.Paired -> statusPillText(status.snapshot.status)
}

private fun formEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
