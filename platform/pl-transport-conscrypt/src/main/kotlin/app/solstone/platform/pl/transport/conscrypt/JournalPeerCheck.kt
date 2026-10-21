// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.crypto.certificateFromPem
import app.solstone.core.crypto.jidFromSpkiDer
import app.solstone.core.identity.ClientCredential
import app.solstone.core.pl.UnknownJournalException
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * One authenticated dial's check of the peer against the paired journal's CA, inside the handshake
 * and before this client sends its Finished.
 *
 * A peer whose certificate is not signed by the pinned CA is not the paired journal: the check ends
 * the handshake and records an unknown-journal sighting, with the journal ID of the self-signed CA
 * the peer presented as its claim. A peer that presents the pinned CA itself as its certificate, or
 * the pinned CA without a certificate it signed, is an unknown journal with no claim. A certificate
 * the pinned CA did sign is the paired journal; any further failure (its dates against a wrong
 * clock, say) is an ordinary handshake failure, decided by the platform's own path validation.
 *
 * Use one instance per handshake.
 */
internal class JournalPeerCheck(credential: ClientCredential) : X509ExtendedTrustManager() {
    private val pinned: List<X509Certificate> = credential.caChainPem.map(::certificateFromPem)
    private val platform: X509ExtendedTrustManager = trustManagersFor(credential)
        .filterIsInstance<X509ExtendedTrustManager>()
        .firstOrNull()
        ?: throw IllegalStateException("no X509 trust manager for the paired journal's CA")

    /** Set when the peer was not the paired journal. */
    @Volatile
    var sighting: Sighting? = null
        private set

    /** Set once the peer's certificate was confirmed against the pinned CA. */
    @Volatile
    var verified: Boolean = false
        private set

    /** What an unknown peer claimed to be. */
    data class Sighting(val claimedJournalId: String?)

    /** [failure], or the unknown-journal sighting this check recorded while it happened. */
    fun explain(failure: Exception, address: String?): Exception =
        sighting?.let { UnknownJournalException(address, it.claimedJournalId, failure) } ?: failure

    private fun requireThePairedJournal(chain: Array<X509Certificate>?) {
        val presented = chain.orEmpty().toList()
        val leaf = presented.firstOrNull() ?: refuse(null)
        if (pinned.any { it.encoded.contentEquals(leaf.encoded) }) refuse(null)
        if (pinned.none { signedBy(leaf, it) }) refuse(claimedJournalId(presented))
    }

    private fun refuse(claimedJournalId: String?): Nothing {
        sighting = Sighting(claimedJournalId)
        throw CertificateException("peer is not the paired journal")
    }

    /** The journal ID of the self-signed P-256 CA the peer presented, preferring its chain's root. */
    private fun claimedJournalId(presented: List<X509Certificate>): String? {
        val ca = presented.asReversed().firstOrNull { signedBy(it, it) } ?: return null
        if (pinned.any { it.encoded.contentEquals(ca.encoded) }) return null
        return runCatching { jidFromSpkiDer(ca.publicKey.encoded) }.getOrNull()
    }

    private fun signedBy(cert: X509Certificate, issuer: X509Certificate): Boolean =
        runCatching { cert.verify(issuer.publicKey) }.isSuccess

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, socket: Socket?) {
        requireThePairedJournal(chain)
        platform.checkServerTrusted(chain, authType, socket)
        verified = true
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) {
        requireThePairedJournal(chain)
        platform.checkServerTrusted(chain, authType, engine)
        verified = true
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
        requireThePairedJournal(chain)
        platform.checkServerTrusted(chain, authType)
        verified = true
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, socket: Socket?) =
        throw CertificateException("client certificates are not accepted here")

    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) =
        throw CertificateException("client certificates are not accepted here")

    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) =
        throw CertificateException("client certificates are not accepted here")

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
}
