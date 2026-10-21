// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.pl.HandshakeFailure
import app.solstone.core.pl.JournalRefusalException
import app.solstone.core.pl.JournalVerdictListener
import java.security.Security
import javax.net.ssl.SSLContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The refusal rule on Conscrypt (BoringSSL), the TLS stack the phone runs, against an in-process
 * TLS 1.3 server that sends a chosen alert. The host suite runs the same cases on JSSE, which
 * reports alerts differently; only this suite proves the device reads BoringSSL's reports.
 *
 * Both client paths run: the direct socket (SSLSocket) and the engine the relay's inner TLS uses.
 */
@RunWith(AndroidJUnit4::class)
class ConscryptRefusalInstrumentedTest {
    private lateinit var pki: TestJournalPki
    private val servers = mutableListOf<ChosenAlertTlsServer>()

    private val paths: List<Pair<String, (Int, JournalVerdictListener) -> DialResult>> = listOf(
        "direct" to { port, listener -> dialDirect(port, pki.credential, listener) },
        "engine" to { port, listener -> dialEngine(port, pki.credential, listener) },
    )

    @Before
    fun setUp() {
        installConscrypt()
        assertNotNull("the Conscrypt provider is installed", Security.getProvider("Conscrypt"))
        assertEquals("Conscrypt", SSLContext.getInstance("TLS", "Conscrypt").provider.name)
        assertEquals("Conscrypt is the first provider", "Conscrypt", Security.getProviders().first().name)
        pki = TestJournalPki.generate()
    }

    @After
    fun tearDown() {
        servers.forEach { it.close() }
    }

    private fun run(ending: ChosenAlertTlsServer.Ending, dial: (Int, JournalVerdictListener) -> DialResult): Pair<DialResult, RecordingVerdicts> {
        val server = ChosenAlertTlsServer(listOf(pki.serverDer, pki.caDer), pki.serverKeys).also { servers += it }
        val heard = RecordingVerdicts()
        val done = server.serveOnce(ending)
        val result = dial(server.port, heard)
        ChosenAlertTlsServer.await(done) { "client saw ${result.error?.stackTraceToString()}" }
        return result to heard
    }

    // Falsified by reading BoringSSL's alert reports wrongly: 49 as anything but a stop, 46 as a
    // counted refusal, 80 as unreachable, or an unassigned code (200), which BoringSSL may not name,
    // as anything but a counted refusal.
    @Test
    fun alertsAfterTheClientFinishedClassifyOnConscrypt() {
        val expected = listOf(
            49 to HandshakeFailure.TLS_ACCESS_DENIED,
            46 to HandshakeFailure.TLS_CERTIFICATE_UNKNOWN,
            80 to HandshakeFailure.TLS_REFUSED,
            200 to HandshakeFailure.TLS_REFUSED,
        )
        for ((code, failure) in expected) {
            for ((path, dial) in paths) {
                val (result, heard) = run(ChosenAlertTlsServer.Ending.AlertAfterClientFinished(code), dial)
                val context = "$path alert $code: ${result.error}"
                android.util.Log.i("RefusalOnConscrypt", "$path alert $code -> ${result.failure} code=${result.alertCode}: ${result.error?.cause?.message}")
                assertEquals(context, failure, result.failure)
                assertTrue(context, result.error is JournalRefusalException)
                assertEquals(context, listOf("refused $failure ${result.alertCode}"), heard.heard)
                assertEquals(context, code, result.alertCode)
            }
        }
    }

    // Falsified by trusting an alert that arrived before this client confirmed the journal.
    @Test
    fun anAccessDeniedBeforeTheClientFinishedIsUnreachableOnConscrypt() {
        for ((path, dial) in paths) {
            val (result, heard) = run(ChosenAlertTlsServer.Ending.AlertBeforeClientFinished(49), dial)
            assertEquals("$path: ${result.error}", HandshakeFailure.UNREACHABLE, result.failure)
            assertTrue("$path", heard.heard.isEmpty())
        }
    }

    // Falsified by counting a reset or a clean close before any answer.
    @Test
    fun aResetOrACloseNotifyBeforeAnyAnswerIsUnreachableOnConscrypt() {
        for (ending in listOf(ChosenAlertTlsServer.Ending.ResetAfterHandshake, ChosenAlertTlsServer.Ending.CloseNotifyAfterHandshake)) {
            for ((path, dial) in paths) {
                val (result, heard) = run(ending, dial)
                assertEquals("$path $ending: ${result.error}", HandshakeFailure.UNREACHABLE, result.failure)
                assertTrue("$path $ending", heard.heard.isEmpty())
            }
        }
    }
}
