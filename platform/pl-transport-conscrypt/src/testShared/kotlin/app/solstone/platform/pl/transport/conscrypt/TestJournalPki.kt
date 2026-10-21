// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.crypto.pem
import app.solstone.core.identity.ClientCredential
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * A journal's certificates for TLS tests, made in-process: a self-signed P-256 CA, a server
 * certificate it signed, and a client credential pinned to it. [other] is a second, unrelated
 * journal.
 */
class TestJournalPki private constructor(
    val caKeys: KeyPair,
    val caDer: ByteArray,
    val serverKeys: KeyPair,
    val serverDer: ByteArray,
    val credential: ClientCredential,
) {
    /** A server certificate this CA signed whose validity ended yesterday. */
    fun expiredServer(): Pair<KeyPair, ByteArray> {
        val keys = p256()
        val day = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        return keys to issue(caKeys, CA_NAME, keys, "journal", ca = false, serverAuth = true, notBefore = now - 30 * day, notAfter = now - day)
    }

    companion object {
        private val CA_NAME = "test journal CA"

        fun generate(): TestJournalPki {
            val caKeys = p256()
            val caDer = issue(caKeys, CA_NAME, caKeys, CA_NAME, ca = true, serverAuth = false)
            val serverKeys = p256()
            val serverDer = issue(caKeys, CA_NAME, serverKeys, "journal", ca = false, serverAuth = true)
            val clientKeys = p256()
            val clientDer = issue(caKeys, CA_NAME, clientKeys, "device", ca = false, serverAuth = false)
            return TestJournalPki(
                caKeys = caKeys,
                caDer = caDer,
                serverKeys = serverKeys,
                serverDer = serverDer,
                credential = ClientCredential(
                    privateKeyPem = pem("PRIVATE KEY", clientKeys.private.encoded),
                    clientCertPem = pem("CERTIFICATE", clientDer),
                    caChainPem = listOf(pem("CERTIFICATE", caDer)),
                ),
            )
        }

        fun p256(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()

        fun issue(
            issuerKeys: KeyPair,
            issuerName: String,
            subjectKeys: KeyPair,
            subjectName: String,
            ca: Boolean,
            serverAuth: Boolean,
            notBefore: Long = System.currentTimeMillis() - 60 * 60 * 1000,
            notAfter: Long = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000,
        ): ByteArray {
            val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
                X500Name("CN=$issuerName"),
                BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE),
                Date(notBefore),
                Date(notAfter),
                X500Name("CN=$subjectName"),
                subjectKeys.public,
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
            if (ca) {
                builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature))
            } else {
                builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
                builder.addExtension(
                    Extension.extendedKeyUsage,
                    false,
                    ExtendedKeyUsage(if (serverAuth) KeyPurposeId.id_kp_serverAuth else KeyPurposeId.id_kp_clientAuth),
                )
            }
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(issuerKeys.private)
            return builder.build(signer).encoded
        }
    }
}
