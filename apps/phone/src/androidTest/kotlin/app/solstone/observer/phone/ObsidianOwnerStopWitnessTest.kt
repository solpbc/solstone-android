// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class ObsidianOwnerStopWitnessTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("phone_owner_stopped", Context.MODE_PRIVATE)
    @Before fun before() { assertTrue(prefs.edit().clear().commit()) }
    @After fun after() { assertTrue(prefs.edit().clear().commit()) }
    @Test fun repeatedStopsHaveDifferentDurableWitnessesWithoutASourceCallback() {
        val store = OwnerStoppedStore(context)
        assertNull(store.stopWitness())
        assertTrue(store.recordStopped()); val first = store.stopWitness()
        assertNotNull(first)
        assertTrue(store.recordStopped()); val second = OwnerStoppedStore(context).stopWitness()
        assertNotNull(second); assertNotEquals(first, second)
        assertEquals(second, prefs.all["stop_witness"])
        assertEquals(true, prefs.all["stopped"])
        store.clear(); assertNull(OwnerStoppedStore(context).stopWitness())
    }
    @Test fun legacyTrueBecomesANewWitnessOnTheNextStop() {
        assertTrue(prefs.edit().putBoolean("stopped", true).commit())
        val store = OwnerStoppedStore(context)
        assertEquals("legacy-stop", store.stopWitness())
        assertTrue(store.recordStopped()); assertNotEquals("legacy-stop", store.stopWitness())
    }
    @Test fun aFailedPreferenceCommitIsReturnedAsFailure() {
        val editor = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(android.content.SharedPreferences.Editor::class.java)) { proxy, method, _ ->
            when (method.name) { "commit" -> false; else -> proxy }
        } as android.content.SharedPreferences.Editor
        val fake = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
            when (method.name) { "edit" -> editor; else -> method.invoke(prefs, *(args ?: emptyArray())) }
        } as android.content.SharedPreferences
        val wrapped = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = fake
        }
        assertFalse(OwnerStoppedStore(wrapped).recordStopped())
    }
}
