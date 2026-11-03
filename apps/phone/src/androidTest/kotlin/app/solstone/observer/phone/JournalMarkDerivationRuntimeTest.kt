// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.pl.journalMarkFromJid
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Argon2 and the locked tables on the Android runtime, from the app's own packaging: the JVM tests
 * hold the algorithm to the shared vectors, and this holds what those tests can't see. The cases are
 * vectors from `tools/mark-vectors`.
 */
@RunWith(AndroidJUnit4::class)
class JournalMarkDerivationRuntimeTest {
    @Test
    fun theVectorsDeriveOnThisRuntime() {
        val runtime = Runtime.getRuntime()
        val started = System.nanoTime()
        val first = journalMarkFromJid("f30ed159-ef46-8e9c-913f-e49f0fe7d201")
        val millis = (System.nanoTime() - started) / 1_000_000
        Log.i("JournalMarkDerivation", "derived in ${millis}ms, max heap ${runtime.maxMemory() / 1_048_576}MiB")

        assertEquals(listOf("piano", "key"), listOf(first.icon1.name, first.icon2.name))
        assertEquals(listOf("blue", "purple"), listOf(first.icon1.colorName, first.icon2.colorName))
        assertEquals(listOf(45, 0), listOf(first.icon1.rot, first.icon2.rot))
        assertEquals(listOf("liquefy", "smock"), first.words)

        assertEquals(listOf("drop-down", "drown"), journalMarkFromJid("9661def7-be18-8d3e-a180-0000000001c5").words)
    }
}
