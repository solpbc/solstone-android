// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

@Composable
fun PhoneStatusPill(
    model: PhoneStatusModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = statusPillKind(model)
    val text = statusPillText(model)
    val liveText = when (kind) {
        StatusPillKind.CONNECTED -> "connected"
        StatusPillKind.SYNCING -> "syncing"
        StatusPillKind.OFFLINE -> "offline"
        StatusPillKind.NOT_PAIRED -> "not paired"
    }
    val pulse = kind == StatusPillKind.SYNCING
    val infinite: InfiniteTransition = rememberInfiniteTransition(label = "statusPulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (pulse) 0.35f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "statusPulseAlpha",
    )
    Surface(
        modifier = modifier
            .testTag("statusPill")
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(if (pulse) pulseAlpha else 1f)
                    .testTag(if (pulse) "statusPulse" else "statusDot"),
            ) {
                PhoneTileDot(state = app.solstone.core.model.SourceState.ON)
            }
            Box(
                Modifier
                    .size(0.dp)
                    .testTag("statusLiveRegion")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(text = liveText)
            }
            Text(
                text = text,
                style = TextStyle(fontFeatureSettings = "tnum"),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .testTag("statusState")
                    .semantics { stateDescription = text },
            )
        }
    }
}
