// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.power

import android.content.Intent
import android.net.Uri

fun GuidanceIntentPreparation.Ready.toIntent(): Intent =
    Intent(action).also { intent ->
        if (data != null) intent.data = Uri.parse(data)
    }
