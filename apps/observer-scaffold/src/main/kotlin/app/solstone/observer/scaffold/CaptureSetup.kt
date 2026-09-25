// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.observer.harness.SourceRegistration

data class CaptureSourceIdentity(
    val sourceId: String,
    val captureForegroundType: app.solstone.platform.fgs.CaptureForegroundType?,
)

data class CaptureSetup(
    val registrations: List<SourceRegistration>,
    val payloadBytesProvider: PayloadBytesProvider,
    val storageOk: () -> Boolean = { true },
)
