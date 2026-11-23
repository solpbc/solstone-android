// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.solstone.core.pl.MigrationClient
import app.solstone.observer.harness.migrationClientActivityLine

/**
 * Scrollable list of paired devices inside the post-pair replacement dialog.
 * Each row carries its activity sub-line when available so devices that share a model
 * name can be told apart before revoking the selected device's access.
 */
@Composable
fun PhoneMigrationDeviceList(
    clients: List<MigrationClient>,
    nowEpochMs: Long,
    onSelect: (MigrationClient) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        clients.forEachIndexed { index, client ->
            if (index > 0) {
                HorizontalDivider(
                    thickness = ShellMetrics.hairline,
                    color = shellHairline,
                )
            }
            val subLine = migrationClientActivityLine(client, nowEpochMs)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ShellMetrics.rowMinHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .semantics(mergeDescendants = true) { role = Role.Button }
                    .clickable { onSelect(client) }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = client.displayLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (subLine != null) {
                        Text(
                            text = subLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = shellSecondaryInk,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Icon(
                    painter = painterResource(R.drawable.phone_chevron_right),
                    contentDescription = null,
                    tint = shellSecondaryInk,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
