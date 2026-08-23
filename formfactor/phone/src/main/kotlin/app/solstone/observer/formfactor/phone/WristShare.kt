// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

sealed interface WristShare {
    data object Unknown : WristShare
    data class Known(val count: Int) : WristShare
}
