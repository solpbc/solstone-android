// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.push

sealed interface DistributorResolution {
    data class Found(val packageName: String) : DistributorResolution
    data object NoneAvailable : DistributorResolution
    data object ToSelect : DistributorResolution
}

interface DistributorPort {
    fun resolveDefault(): DistributorResolution
    fun available(): List<String>
    val ownPackage: String
    fun save(pkg: String)
    fun register(vapidKey: String)
    fun unregister()
}
