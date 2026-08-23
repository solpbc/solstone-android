// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

fun sourceEarnsSwitch(sourceId: String): Boolean =
    sourceId == "audio" || sourceId == "location"
