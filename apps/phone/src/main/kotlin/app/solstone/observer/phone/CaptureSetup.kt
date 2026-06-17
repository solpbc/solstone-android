// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.spool.PayloadBytesProvider

data class CaptureSetup(
    val engines: List<ContinuousSourceEngine>,
    val payloadBytesProvider: PayloadBytesProvider,
)
