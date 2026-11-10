// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.util.Locale

data class PhoneIntakeSnapshot(
    val bySource: Map<String, PhoneIntakeReading> = emptyMap(),
)

val LocalPhoneIntake = staticCompositionLocalOf { PhoneIntakeSnapshot() }

val LocalPhoneDistanceSystem = staticCompositionLocalOf { DistanceSystem.MILES }

/** Source ids in the order the deck was given them. Import is added after these. */
val LocalPhoneSourceOrder = staticCompositionLocalOf { emptyList<String>() }

@Composable
internal fun PhoneIntakeFactRows(sourceId: String) {
    val reading = LocalPhoneIntake.current.bySource[sourceId]
    val now = LocalPhoneStatusNow.current.nowEpochMs()
    val zone = ZoneId.systemDefault()
    val locale = Locale.getDefault()
    val system = LocalPhoneDistanceSystem.current
    val kind = intakeKind(sourceId)
    PaneFactRow(
        label = "so far",
        value = formatSoFar(reading, kind, system, now, zone, locale),
        modifier = Modifier.testTag("intakeSoFar-$sourceId"),
    )
    val last = formatLastTakenIn(reading, now, zone)
    if (last != null) {
        PaneRowDivider()
        PaneFactRow(
            label = "last taken in",
            value = last,
            modifier = Modifier.testTag("intakeLast-$sourceId"),
        )
    }
}

@Composable
internal fun PhoneIntakeStatusRows(
    onOpenSource: (String) -> Unit,
    onOpenImport: (() -> Unit)?,
) {
    val order = LocalPhoneSourceOrder.current.ifEmpty { phoneSourceLabels.keys.toList() }
    val ids = (order + "import").distinct()
    Spacer(Modifier.height(ShellMetrics.sectionSpacing))
    Row(Modifier.fillMaxWidth().padding(start = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = "so far",
            style = MaterialTheme.typography.bodySmall,
            color = shellSecondaryInk,
            modifier = Modifier.weight(1f).testTag("intakeHeading-soFar"),
        )
        Text(
            text = "last taken in",
            style = MaterialTheme.typography.bodySmall,
            color = shellSecondaryInk,
            modifier = Modifier.weight(1f).testTag("intakeHeading-last"),
        )
    }
    ids.forEach { sourceId ->
        PhoneIntakeStatusRow(
            sourceId = sourceId,
            onOpen = {
                if (sourceId == "import") onOpenImport?.invoke() else onOpenSource(sourceId)
            },
        )
    }
}

@Composable
private fun PhoneIntakeStatusRow(sourceId: String, onOpen: () -> Unit) {
    val reading = LocalPhoneIntake.current.bySource[sourceId]
    val now = LocalPhoneStatusNow.current.nowEpochMs()
    val zone = ZoneId.systemDefault()
    val locale = Locale.getDefault()
    val system = LocalPhoneDistanceSystem.current
    val kind = intakeKind(sourceId)
    val total = formatStatusTotal(reading, kind, system, locale)
    val last = formatLastTakenIn(reading, now, zone)
    val glyph = if (sourceId == "import") R.drawable.phone_import else sourceGlyph(sourceId)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onOpen)
            .testTag("intakeRow-$sourceId"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(glyph),
            contentDescription = null,
            tint = shellSecondaryInk,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(text = sourceLabel(sourceId), style = MaterialTheme.typography.bodyLarge)
            if (total == null || last == null) {
                Text(
                    text = "nothing yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = shellSecondaryInk,
                    modifier = Modifier.testTag("intakeEmpty-$sourceId"),
                )
            } else {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = total,
                        style = MaterialTheme.typography.bodySmall,
                        color = shellSecondaryInk,
                        modifier = Modifier.weight(1f).testTag("intakeTotal-$sourceId"),
                    )
                    Text(
                        text = last,
                        style = MaterialTheme.typography.bodySmall,
                        color = shellSecondaryInk,
                        modifier = Modifier.weight(1f).testTag("intakeLast-$sourceId"),
                    )
                }
            }
        }
    }
}
