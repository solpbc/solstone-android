// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhoneHomeTileStoreTest {
    @Test
    fun writesAreVisibleToFreshStoreInstance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "phone-home-tiles-${UUID.randomUUID()}"
        try {
            SharedPreferencesPhoneHomeTileStore(context, name).setHasTile("audio", true)

            assertTrue(SharedPreferencesPhoneHomeTileStore(context, name).hasTile("audio"))
        } finally {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
