// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

interface EndpointStore {
    fun save(endpoint: DirectEndpoint)
    fun load(): DirectEndpoint?
    fun clear()
}
