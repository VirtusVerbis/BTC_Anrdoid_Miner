package com.btcminer.android.network

import android.util.Base64
import java.security.MessageDigest
import java.util.Collections
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SNIHostName

/**
 * One-time TLS connection to a stratum host to capture the server certificate's SPKI pin.
 * Used when the user checks "Pin Mining Pool Certificate" and taps SAVE in Config.
 * Host normalization must match [com.btcminer.android.mining.NativeMiningEngine] (strip scheme, first segment).
 */
object StratumPinCapture {

    /**
     * Normalize stratum URL to host only: strip stratum+tcp://, stratum+ssl://, tcp://, take first segment.
     * Must match NativeMiningEngine host derivation for consistent storage key.
     */
    fun normalizeHost(stratumUrl: String): String =
        stratumUrl.trim()
            .removePrefix("stratum+tcp://").removePrefix("stratum+ssl://").removePrefix("tcp://")
            .split("/").first().trim()

    /**
     * Connect to host:port over TLS, read the leaf certificate's SPKI, return pin string "sha256/...".
     * Throws on connection failure or if no certificate.
     */
    fun capturePin(host: String, port: Int): String {
        val socket = SSLSocketFactory.getDefault().createSocket(host, port)
        try {
            if (socket !is SSLSocket) throw IllegalStateException("Expected SSLSocket")
            val sslSocket = socket
            sslSocket.soTimeout = 10_000
            val params: SSLParameters = sslSocket.sslParameters
            params.serverNames = Collections.singletonList(SNIHostName(host))
            sslSocket.sslParameters = params
            sslSocket.startHandshake()
            val session = socket.session ?: throw IllegalStateException("No SSL session")
            val certs = session.peerCertificates
            if (certs.isNullOrEmpty()) throw IllegalStateException("No peer certificates")
            val leaf = certs[0]
            if (leaf !is java.security.cert.X509Certificate) throw IllegalStateException("Leaf cert not X509")
            val spkiDer = leaf.publicKey.encoded
            val hash = MessageDigest.getInstance("SHA-256").digest(spkiDer)
            val base64 = Base64.encodeToString(hash, Base64.NO_WRAP)
            return "sha256/$base64"
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }
}
