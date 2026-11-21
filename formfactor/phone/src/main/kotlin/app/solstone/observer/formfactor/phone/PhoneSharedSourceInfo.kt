// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.solstone.core.i18n.phoneText
import app.solstone.core.model.SourceState
import app.solstone.core.pl.*
import java.time.ZoneId

data class PhoneSharedSourceInfo(val known: KnownSources? = null, val open: () -> Unit = {})
val LocalPhoneSharedSources = staticCompositionLocalOf { PhoneSharedSourceInfo() }

internal fun sharedKind(id: String) = id == "obsidian" || id == "calendar"

@Composable
internal fun otherDevices(id: String, state: SourceState?, facts: Boolean = false): List<SharedSourceDevice> {
    if (!sharedKind(id) || state == null) return emptyList()
    val local = state != SourceState.OFF && state != SourceState.READY_TO_SET_UP
    if (local != facts) return emptyList()
    if (state == SourceState.READY_TO_SET_UP) {
        val pending = if (id == "obsidian") {
            val o = LocalPhoneObsidian.current.state
            o.settingUp || o.warningVault != null || o.fault != null
        } else {
            val c = LocalPhoneCalendar.current.state
            c.permissionGranted && !c.enabled && !c.resume && (c.rows.isNotEmpty() || c.countStatus != null)
        }
        if (pending) return emptyList()
    }
    return LocalPhoneSharedSources.current.known?.sources?.get(id).orEmpty()
}

internal fun sharedHeading(count: Int) = phoneText("android.source.shared.devices", "count" to count)

internal fun SharedSourceRow.line(name: String?, now: Long, spoken: Boolean = false): String {
    val stateText = when (state) {
        "paused" -> sourceStateCopy(SourceState.PAUSED)
        "needs_attention" -> sourceStateCopy(SourceState.NEEDS_ATTENTION)
        else -> null
    }
    val time = at?.let {
        val minutes = (now - it.toEpochMilli()) / 60_000L
        if (spoken && minutes in 1..59) phoneText("android.about.freshnessminutes", "count" to minutes)
        else formatRelative(it.toEpochMilli(), now, ZoneId.systemDefault())
    }
    val parts = listOfNotNull(name, stateText, time)
    return when (parts.size) {
        3 -> phoneText("android.source.shared.triple", "first" to parts[0], "state" to parts[1], "relative" to parts[2])
        2 -> phoneText("android.source.shared.pair", "first" to parts[0], "second" to parts[1])
        else -> parts.firstOrNull().orEmpty()
    }
}

@Composable
internal fun SharedMarker() {
    val ink = shellSecondaryInk
    Canvas(Modifier.size(12.dp)) {
        drawCircle(ink, size.minDimension * .40f, style = Stroke(size.minDimension * .12f))
        drawCircle(ink, size.minDimension * .13f)
    }
}

@Composable
internal fun SharedTileLine(device: SharedSourceDevice, now: Long) {
    val measure = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val row = device.rows.first()
        val full = row.line(device.label, now)
        val limit = Constraints(maxWidth = constraints.maxWidth)
        val shown = remember(full, style, limit, measure) {
            fun fits(value: String) = !measure.measure(value, style, maxLines = 3, constraints = limit).hasVisualOverflow
            if (fits(full)) full else {
                var low = 0
                var high = device.label.length
                while (low < high) {
                    val mid = (low + high + 1) / 2
                    if (fits(row.line(device.label.take(mid) + "…", now))) low = mid else high = mid - 1
                }
                row.line(device.label.take(low) + "…", now)
            }
        }
        Text(shown, style = style, color = shellSecondaryInk)
    }
}

@Composable
internal fun SharedSourceCard(id: String, state: SourceState?) {
    val shared = LocalPhoneSharedSources.current
    val unconfigured = state == SourceState.READY_TO_SET_UP || state == SourceState.OFF
    LaunchedEffect(id, unconfigured) { if (unconfigured) shared.open() }
    val devices = otherDevices(id, state)
    if (devices.isEmpty()) return
    val now = LocalPhoneStatusNow.current.nowEpochMs()
    PaneCard {
        Row(Modifier.padding(horizontal = ShellMetrics.surfacePadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SharedMarker()
            Text(sharedHeading(devices.size), color = shellSecondaryInk)
        }
        for (device in devices) {
            PaneRowDivider()
            Row(Modifier.padding(horizontal = ShellMetrics.surfacePadding, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(when (device.type) {
                    "phone" -> R.drawable.shared_phone
                    "tablet" -> R.drawable.shared_tablet
                    "laptop" -> R.drawable.shared_laptop
                    "desktop" -> R.drawable.phone_source_screen
                    "wearable", "watch" -> R.drawable.phone_source_watch
                    else -> R.drawable.shared_device
                }), contentDescription = null, tint = shellSecondaryInk, modifier = Modifier.size(20.dp))
                Column {
                    Text(device.label)
                    for (row in device.rows) {
                        val line = row.line(row.detail, now)
                        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = shellSecondaryInk)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SharedSourceFacts(id: String, state: SourceState?) {
    val now = LocalPhoneStatusNow.current.nowEpochMs()
    otherDevices(id, state, facts = true).forEachIndexed { index, device ->
        PaneFactRow(if (index == 0) phoneText("android.source.shared.also") else "", device.rows.first().line(device.label, now))
    }
}
