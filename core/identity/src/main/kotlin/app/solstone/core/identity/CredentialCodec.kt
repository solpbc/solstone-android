// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

import app.solstone.core.crypto.certificateFromPem
import app.solstone.core.crypto.pem
import app.solstone.core.crypto.privateKeyFromPem
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLException

data class DecodedCredential(
    val privateKey: PrivateKey,
    val clientCert: X509Certificate,
    val caChain: List<X509Certificate>,
)

object CredentialCodec {
    private val password = "rogbid-pl".toCharArray()

    fun encode(
        privateKey: PrivateKey,
        clientCert: X509Certificate,
        caChain: List<X509Certificate>,
    ): ClientCredential = ClientCredential(
        privateKeyPem = pem("PRIVATE KEY", privateKey.encoded),
        clientCertPem = pem("CERTIFICATE", clientCert.encoded),
        caChainPem = caChain.map { pem("CERTIFICATE", it.encoded) },
    )

    fun decode(credential: ClientCredential): DecodedCredential = DecodedCredential(
        privateKey = privateKeyFromPem(credential.privateKeyPem),
        clientCert = certificateFromPem(credential.clientCertPem),
        caChain = credential.caChainPem.map { certificateFromPem(it) },
    )

    fun keyManagers(credential: ClientCredential): Array<KeyManager> {
        val decoded = decode(credential)
        val keys = KeyStore.getInstance(KeyStore.getDefaultType())
        keys.load(null)
        val privateKey = decoded.privateKey
        if (privateKey !is ECPrivateKey) {
            throw SSLException("stored PL private key is not EC")
        }
        val keyChain = ArrayList<Certificate>()
        keyChain += decoded.clientCert
        keyChain += decoded.caChain
        keys.setKeyEntry("client", privateKey, password, keyChain.toTypedArray())
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keys, password)
        return kmf.keyManagers
    }
}
