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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * The status pill in the app bar.
 *
 * Two defects fixed at their cause:
 *
 * ⚠ **The dot was hardcoded to `SourceState.ON`**, so it drew the green "on" disc under
 * *every* pill state — a green dot beside the words `not paired`, which is the pill
 * contradicting itself. § 3's table assigns a dot per state (`connected` a green dot,
 * `offline` and `not paired` a *calm* one, `syncing` a pulse) and the pill now resolves
 * one. Same shape as the iOS defect where the pill rendered two of its four contract
 * states at once.
 *
 * ⚠ **The pill was a solid `primaryContainer` — full-strength sol orange** — which made
 * the loudest object on home a problem state, and put a decoration orange where the
 * approved mock puts a quiet cream-bright surface with a hairline. The pill is a
 * control on the ground, not a banner: the *dot* carries the state, and the surface
 * stays out of the way.
 */
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
    // A calm dot is the state having nothing to celebrate and nothing to alarm about:
    // the ring shape at reduced ink. Derived from the scheme so it holds in both
    // appearances rather than pinning a light-ground grey.
    val calm = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val mark = when (kind) {
        StatusPillKind.CONNECTED, StatusPillKind.SYNCING -> TileDotMark.DISC
        StatusPillKind.OFFLINE, StatusPillKind.NOT_PAIRED -> TileDotMark.RING
    }
    val dotColor = when (kind) {
        StatusPillKind.CONNECTED -> LocalStatusOnGreen.current
        StatusPillKind.SYNCING -> MaterialTheme.colorScheme.primaryContainer
        StatusPillKind.OFFLINE, StatusPillKind.NOT_PAIRED -> calm
    }
    Box(
        modifier = modifier
            .testTag("statusPill")
            .heightIn(min = 34.dp)
            .shellSurface(shellSurface, shellHairline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .alpha(if (pulse) pulseAlpha else 1f)
                    .testTag(if (pulse) "statusPulse" else "statusDot"),
            ) {
                PhoneTileDot(mark = mark, color = dotColor)
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
                style = MaterialTheme.typography.bodyMedium.merge(
                    TextStyle(fontFeatureSettings = "tnum"),
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(start = 7.dp)
                    .testTag("statusState")
                    .semantics { stateDescription = text },
            )
        }
    }
}
