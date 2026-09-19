// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.StoreInspectResult

interface EndpointStore {
    fun save(endpoint: DirectEndpoint)
    fun load(): DirectEndpoint?
    fun clear()
    fun inspect(): StoreInspectResult<DirectEndpoint> =
        load()?.let { StoreInspectResult.Ready(it) } ?: StoreInspectResult.Missing
}
