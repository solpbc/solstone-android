// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.solstone.core.push.DistributorPort
import app.solstone.core.push.DistributorResolution
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.ResolvedDistributor

class UnifiedPushDistributorPort(
    private val context: Context,
) : DistributorPort {

    override fun resolveDefault(): DistributorResolution =
        when (val resolved = UnifiedPush.resolveDefaultDistributor(context)) {
            is ResolvedDistributor.Found -> DistributorResolution.Found(resolved.packageName)
            is ResolvedDistributor.NoneAvailable -> DistributorResolution.NoneAvailable
            is ResolvedDistributor.ToSelect -> DistributorResolution.ToSelect
        }

    override fun available(): List<String> =
        UnifiedPush.getDistributors(context)

    override val ownPackage: String
        get() = context.packageName

    override fun save(pkg: String) {
        UnifiedPush.saveDistributor(context, pkg)
    }

    override fun register(vapidKey: String) {
        UnifiedPush.register(
            context = context,
            instance = INSTANCE_DEFAULT,
            vapid = vapidKey,
        )
    }

    override fun unregister() {
        UnifiedPush.unregister(context, INSTANCE_DEFAULT)
    }

    override fun installedSince(pkg: String): Long? =
        try {
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(pkg, 0)
                }
            info.firstInstallTime
        } catch (_: Throwable) {
            null
        }
}
