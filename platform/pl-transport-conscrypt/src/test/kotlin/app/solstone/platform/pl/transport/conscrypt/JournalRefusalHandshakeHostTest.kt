// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.crypto.jidFromSpkiDer
import app.solstone.core.pl.HandshakeFailure
import app.solstone.core.pl.HandshakeRefusalTracker
import app.solstone.core.pl.HandshakeStop
import app.solstone.core.pl.JournalRefusalException
import app.solstone.core.pl.JournalVerdictListener
import app.solstone.core.pl.REFUSAL_LIMIT
import app.solstone.core.pl.REFUSAL_SPACING_MILLIS
import app.solstone.core.pl.RefusalRecord
import app.solstone.core.pl.RefusalRecordRead
import app.solstone.core.pl.RefusalRecordStore
import app.solstone.core.pl.UnknownJournalException
import app.solstone.core.pl.unknownJournalIn
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.cert.CertificateFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.ByteString.Companion.toByteString

/**
 * The refusal rule against a real TLS 1.3 server on the host JVM, whose client is JSSE. The same
 * cases run on Conscrypt in the device suite.
 */
class JournalRefusalHandshakeHostTest {
    private val pki = TestJournalPki.generate()
    private val servers = mutableListOf<ChosenAlertTlsServer>()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.close() }
    }

    private fun journal(finishAfterEnding: Boolean = false) =
        ChosenAlertTlsServer(listOf(pki.serverDer, pki.caDer), pki.serverKeys, finishAfterEnding).also { servers += it }

    // Falsified by narrowing the refusal arm to known codes, or by mapping every refusal alike:
    // 46 would stop a device whose journal only failed to read its pairing records, and 80 or an
    // unassigned code would read as unreachable and retry forever.
    @Test
    fun anAlertAfterTheClientFinishedIsTheJournalsVerdictOnBothPaths() {
        val expected = mapOf(
            49 to HandshakeFailure.TLS_ACCESS_DENIED,
            46 to HandshakeFailure.TLS_CERTIFICATE_UNKNOWN,
            80 to HandshakeFailure.TLS_REFUSED,
            200 to HandshakeFailure.TLS_REFUSED,
        )
        for ((code, failure) in expected) {
            for ((path, dial) in listOf<Pair<String, (Int, JournalVerdictListener) -> DialResult>>(
                "direct" to { port, listener -> dialDirect(port, pki.credential, listener) },
                "engine" to { port, listener -> dialEngine(port, pki.credential, listener) },
            )) {
                val server = journal()
                val heard = RecordingVerdicts()
                val done = server.serveOnce(ChosenAlertTlsServer.Ending.AlertAfterClientFinished(code))
                val result = dial(server.port, heard)
                ChosenAlertTlsServer.await(done)
                assertEquals(failure, result.failure, "$path alert $code: ${result.error}")
                assertIs<JournalRefusalException>(result.error, "$path alert $code")
                assertEquals(listOf("refused $failure ${result.alertCode}"), heard.heard, "$path alert $code")
                assertEquals(code, result.alertCode, "$path alert $code")
            }
        }
    }

    // Falsified by trusting an alert that arrives before this client confirmed the journal: any
    // peer on the path could then unpair the device with one forged access denied.
    @Test
    fun anAccessDeniedBeforeTheClientFinishedIsNotAVerdict() {
        for (dial in listOf<(Int, JournalVerdictListener) -> DialResult>(
            { port, listener -> dialDirect(port, pki.credential, listener) },
            { port, listener -> dialEngine(port, pki.credential, listener) },
        )) {
            val server = journal()
            val heard = RecordingVerdicts()
            val done = server.serveOnce(ChosenAlertTlsServer.Ending.AlertBeforeClientFinished(49))
            val result = dial(server.port, heard)
            ChosenAlertTlsServer.await(done)
            assertEquals(HandshakeFailure.UNREACHABLE, result.failure, "${result.error}")
            assertTrue(heard.heard.isEmpty())
        }
    }

    // Falsified by counting a clean close or a reset as a refusal: a journal restarting, or a
    // network dropping the connection, would walk a paired device toward the unpaired state.
    @Test
    fun aCloseNotifyOrAResetBeforeAnyAnswerIsUnreachable() {
        for (ending in listOf(ChosenAlertTlsServer.Ending.CloseNotifyAfterHandshake, ChosenAlertTlsServer.Ending.ResetAfterHandshake)) {
            for (dial in listOf<(Int, JournalVerdictListener) -> DialResult>(
                { port, listener -> dialDirect(port, pki.credential, listener) },
                { port, listener -> dialEngine(port, pki.credential, listener) },
            )) {
                val server = journal()
                val heard = RecordingVerdicts()
                val done = server.serveOnce(ending)
                val result = dial(server.port, heard)
                ChosenAlertTlsServer.await(done)
                assertEquals(HandshakeFailure.UNREACHABLE, result.failure, "$ending: ${result.error}")
                assertTrue(heard.heard.isEmpty(), "$ending")
            }
        }
    }

    // Seven refusals of 80, each after a completed client-side TLS 1.3 handshake and 300 s apart,
    // stop the credential. Falsified by a tracker fed only the opener's errors (the refusal arrives
    // on the first read, after the dial returned) or by an 80 classified as unreachable.
    @Test
    fun sevenRefusalsOf80AtTheSpacingStop() {
        var now = 1_000_000_000L
        val tracker = HandshakeRefusalTracker(MemoryRecords(), wallMillis = { now }, monotonicMillis = { now })
        val credential = "sha256:client"
        var stop: HandshakeStop? = null
        repeat(REFUSAL_LIMIT) { index ->
            val server = journal()
            val done = server.serveOnce(ChosenAlertTlsServer.Ending.AlertAfterClientFinished(80))
            val listener = object : JournalVerdictListener {
                override fun onAccepted() = tracker.accepted(credential)
                override fun onRefused(refusal: JournalRefusalException) {
                    stop = tracker.failed(credential, refusal.failure)
                }
            }
            val result = dialDirect(server.port, pki.credential, listener)
            ChosenAlertTlsServer.await(done)
            assertEquals(HandshakeFailure.TLS_REFUSED, result.failure)
            if (index < REFUSAL_LIMIT - 1) assertNull(stop, "stopped after ${index + 1}")
            now += REFUSAL_SPACING_MILLIS
        }
        assertEquals(HandshakeStop.REFUSALS_EXHAUSTED, stop)
        assertEquals(REFUSAL_LIMIT, tracker.current(credential)?.refusals)
    }

    // Falsified by treating a certificate the pinned CA never signed as the journal's own failure:
    // the owner would never learn a different journal answers at the saved address, and the claim
    // would be lost.
    @Test
    fun aPeerWithAnotherCaIsAnUnknownJournalAtItsAddressWithItsClaim() {
        val otherCa = TestJournalPki.p256()
        val otherCaDer = TestJournalPki.issue(otherCa, "other CA", otherCa, "other CA", ca = true, serverAuth = false)
        val otherLeafKeys = TestJournalPki.p256()
        val otherLeaf = TestJournalPki.issue(otherCa, "other CA", otherLeafKeys, "journal", ca = false, serverAuth = true)
        val server = ChosenAlertTlsServer(listOf(otherLeaf, otherCaDer), otherLeafKeys).also { servers += it }
        val done = server.serveOnce(ChosenAlertTlsServer.Ending.CloseNotifyAfterHandshake)
        val heard = RecordingVerdicts()
        val result = dialDirect(server.port, pki.credential, heard)
        ChosenAlertTlsServer.await(done)

        assertEquals(HandshakeFailure.UNKNOWN_JOURNAL, result.failure)
        val unknown = assertNotNull(unknownJournalIn(assertNotNull(result.error)))
        assertEquals("127.0.0.1:${server.port}", unknown.address)
        assertEquals(jidFromSpkiDer(certificate(otherCaDer).publicKey.encoded), unknown.claimedJournalId)
        assertTrue(heard.heard.isEmpty())
    }

    // Through the relay the sighting carries no address of its own. Falsified by labelling a relay
    // sighting with the relay's address as if the peer lived there.
    @Test
    fun anUnknownJournalThroughTheEngineHasNoAddress() {
        val otherCa = TestJournalPki.p256()
        val otherCaDer = TestJournalPki.issue(otherCa, "other CA", otherCa, "other CA", ca = true, serverAuth = false)
        val otherLeafKeys = TestJournalPki.p256()
        val otherLeaf = TestJournalPki.issue(otherCa, "other CA", otherLeafKeys, "journal", ca = false, serverAuth = true)
        val server = ChosenAlertTlsServer(listOf(otherLeaf, otherCaDer), otherLeafKeys).also { servers += it }
        val done = server.serveOnce(ChosenAlertTlsServer.Ending.CloseNotifyAfterHandshake)
        val result = dialEngine(server.port, pki.credential, null)
        ChosenAlertTlsServer.await(done)

        val unknown = assertNotNull(unknownJournalIn(assertNotNull(result.error)))
        assertNull(unknown.address)
        assertEquals(jidFromSpkiDer(certificate(otherCaDer).publicKey.encoded), unknown.claimedJournalId)
    }

    // A certificate that chains to the pinned CA but fails another check (here, its dates) is the
    // paired journal failing an ordinary check, so unreachable. Falsified by attributing every
    // certificate failure to another peer.
    @Test
    fun anExpiredChainToThePinnedCaIsNotAnUnknownJournal() {
        val (keys, expired) = pki.expiredServer()
        val server = ChosenAlertTlsServer(listOf(expired, pki.caDer), keys).also { servers += it }
        val done = server.serveOnce(ChosenAlertTlsServer.Ending.CloseNotifyAfterHandshake)
        val result = dialDirect(server.port, pki.credential, null)
        ChosenAlertTlsServer.await(done)

        assertEquals(HandshakeFailure.UNREACHABLE, result.failure, "${result.error}")
        assertNull(unknownJournalIn(assertNotNull(result.error)))
    }

    // A peer presenting the paired CA as its own certificate, or the paired CA beside a leaf that CA
    // never signed, is an unknown journal that claims nothing. Falsified by reading the claim off
    // the pinned CA (it would name the paired journal) or by accepting the peer.
    @Test
    fun thePairedCaWithoutALeafItSignedIsAnUnknownJournalWithNoClaim() {
        val asItsOwnLeaf = ChosenAlertTlsServer(listOf(pki.caDer), pki.caKeys).also { servers += it }
        var done = asItsOwnLeaf.serveOnce(ChosenAlertTlsServer.Ending.CloseNotifyAfterHandshake)
        var result = dialDirect(asItsOwnLeaf.port, pki.credential, null)
        ChosenAlertTlsServer.await(done)
        assertEquals(HandshakeFailure.UNKNOWN_JOURNAL, result.failure, "${result.error}")
        assertNull(assertNotNull(unknownJournalIn(assertNotNull(result.error))).claimedJournalId)

        val strangerKeys = TestJournalPki.p256()
        val stranger = TestJournalPki.issue(strangerKeys, "stranger", strangerKeys, "journal", ca = false, serverAuth = true)
        val beside = ChosenAlertTlsServer(listOf(stranger, pki.caDer), strangerKeys).also { servers += it }
        done = beside.serveOnce(ChosenAlertTlsServer.Ending.CloseNotifyAfterHandshake)
        result = dialDirect(beside.port, pki.credential, null)
        ChosenAlertTlsServer.await(done)
        assertEquals(HandshakeFailure.UNKNOWN_JOURNAL, result.failure, "${result.error}")
        assertNull(assertNotNull(unknownJournalIn(assertNotNull(result.error))).claimedJournalId)
    }

    // The relay's last binary message carries the journal's encrypted alert, then the relay closes
    // normally: that is still the journal's verdict. Falsified by letting the normal close end the
    // read before the queued alert record is decrypted.
    @Test
    fun anAlertRecordAsTheRelaysLastMessageThenANormalCloseIsTheVerdict() {
        val result = throughRelayBridge(closeCode = 1000)
        assertEquals(HandshakeFailure.TLS_REFUSED, result.failure, "${result.error}")
        assertEquals(80, result.alertCode)
    }

    // A relay that terminated the tunnel said why the carrier ended, whatever it carried before.
    // Falsified by naming the queued alert a refusal over the relay's own close.
    @Test
    fun aRelayTerminationCloseIsUnreachable() {
        val result = throughRelayBridge(closeCode = 4401)
        assertEquals(HandshakeFailure.UNREACHABLE, result.failure, "${result.error}")
        assertIs<RelayWebSocketClosedException>(result.error)
    }

    private fun throughRelayBridge(closeCode: Int): DialResult {
        val server = journal(finishAfterEnding = true)
        val done = server.serveOnce(ChosenAlertTlsServer.Ending.AlertAfterClientFinished(80))
        val relayClosed = CountDownLatch(1)
        val socket = Socket(InetAddress.getByName("127.0.0.1"), server.port)
        val out = socket.getOutputStream()
        val duplex = OkHttpWebSocketDuplex(
            object : BinaryWebSocket {
                override fun send(bytes: okio.ByteString): Boolean =
                    runCatching { out.write(bytes.toByteArray()); out.flush() }.isSuccess

                override fun close(code: Int, reason: String?) {
                    socket.close()
                }
            },
        )
        thread(isDaemon = true) {
            val buffer = ByteArray(16 * 1024)
            try {
                val input = socket.getInputStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    duplex.receive(buffer.copyOf(n).toByteString())
                }
            } catch (_: IOException) {
            }
            duplex.closeFromWebSocket(closeCode, "closed")
            relayClosed.countDown()
        }
        val relay = openRelayClient(
            host = "127.0.0.1",
            port = 443,
            tunnelOpener = TunnelOpener { duplex },
            tlsMode = RelayTlsMode.Authenticated(pki.credential),
        )
        // The journal's alert and then the relay's close have both arrived before the first read.
        ChosenAlertTlsServer.await(done)
        assertTrue(relayClosed.await(10, TimeUnit.SECONDS))
        return try {
            relay.client.request("GET", "/app/network/api/status", emptyMap(), ByteArray(0))
            DialResult(null, null, null)
        } catch (e: Throwable) {
            DialResult(app.solstone.core.pl.handshakeFailureOf(e), app.solstone.core.pl.journalRefusalIn(e)?.alertCode, e)
        } finally {
            relay.close()
        }
    }

    private fun certificate(der: ByteArray) =
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as java.security.cert.X509Certificate

    private class MemoryRecords : RefusalRecordStore {
        private var record: RefusalRecord? = null
        override fun read(): RefusalRecordRead = record?.let { RefusalRecordRead.Present(it) } ?: RefusalRecordRead.Absent
        override fun write(record: RefusalRecord) {
            this.record = record
        }
    }
}
