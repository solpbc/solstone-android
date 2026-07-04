// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.metadata

fun parseBatteryTemperatureC(present: Boolean, tenthsC: Int): Double? =
    if (!present) null else tenthsC / 10.0
