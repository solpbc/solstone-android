// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.observer.scaffold.ObserverAppContainer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealFlavorOpportunisticSyncRuntimeTest {
    /**
     * AC5a red proof: before ACCESS_NETWORK_STATE, real opportunistic sync degraded during network callback registration.
     *
     * Gate: `make ci-device` runs this via class-filtered `:apps:phone:pixel5api35RealDebugAndroidTest`.
     */
    @Test
    fun realFlavorRegistersNetworkCallbackWithoutDegrading() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val container = ObserverAppContainer(context, phoneSpec)
        try {
            val opportunisticSync = container.flavor.opportunisticSync
            assertNotNull(opportunisticSync)

            opportunisticSync!!.start()

            assertFalse(opportunisticSync.isDegraded())
        } finally {
            container.close()
        }
    }
}
