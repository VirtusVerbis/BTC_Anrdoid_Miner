package com.btcminer.android.mining

import android.util.Base64
import com.btcminer.android.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal Stratum v1 TCP client. Supports optional TLS.
 * Sends subscribe (id 1), authorize (id 2), submit (id 3+); parses notify, set_difficulty,
 * set_extranonce, client.reconnect, and RPC responses.
 */
class StratumClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val useTls: Boolean = false,
    private val stratumPin: String? = null,
    private val onReconnectRequest: ((host: String, port: Int) -> Unit)? = null,
    private val onTemplateReceived: (() -> Unit)? = null,
    private val onConnectionLost: (() -> Unit)? = null,
) {
    private val socketRef = AtomicReference<Socket?>(null)
    private val writerRef = AtomicReference<PrintWriter?>(null)
    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val readerThreadRef = AtomicReference<Thread?>(null)

    private val currentJob = AtomicReference<StratumJob?>(null)
    private val currentDifficulty = AtomicReference(1.0)
    private val extranonce1Hex = AtomicReference<String?>(null)
    private val extranonce2Size = AtomicReference(4)
    private val authorizeResultRef = AtomicReference<Boolean?>(null)
    private val authorizeErrorRef = AtomicReference<String?>(null)
    private val cleanJobsInvalidation = AtomicBoolean(false)
    private val pendingSubmitCallbacks = ConcurrentHashMap<Int, (Boolean, String?) -> Unit>()

    private val requestId = AtomicInteger(2) // 1=subscribe, 2=authorize, 3+=submit

    private companion object {
        private const val LOG_TAG = "Stratum"

        private fun stratumPinningTrustManager(expectedPin: String): X509TrustManager =
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { }
                override fun getAcceptedIssuers(): Array<X509Certificate>? = null
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) throw javax.net.ssl.SSLPeerUnverifiedException("No server certificates")
                    val leaf = chain[0]
                    val spkiDer = leaf.publicKey.encoded
                    val hash = MessageDigest.getInstance("SHA-256").digest(spkiDer)
                    val base64 = Base64.encodeToString(hash, Base64.NO_WRAP)
                    val actualPin = "sha256/$base64"
                    if (actualPin != expectedPin) {
                        throw javax.net.ssl.SSLPeerUnverifiedException("Certificate pin mismatch")
                    }
                }
            }
    }

    fun getCurrentJob(): StratumJob? = currentJob.get()
    fun getCurrentDifficulty(): Double = currentDifficulty.get()
    fun getExtranonce1Hex(): String? = extranonce1Hex.get()
    fun getExtranonce2Size(): Int = extranonce2Size.get()
    fun isRunning(): Boolean = running.get()
    /** True when socket is open and subscribe+authorize have succeeded. */
    fun isConnected(): Boolean = connected.get()

    /** Returns true if the last mining.notify had cleanJobs=true (caller should abort current job). */
    fun consumeCleanJobsInvalidation(): Boolean = cleanJobsInvalidation.getAndSet(false)

    /**
     * Connect, subscribe, authorize. Returns error message or null on success.
     */
    fun connect(): String? {
        if (running.getAndSet(true)) return null
        val err = doConnect()
        if (err != null) {
            disconnect()
            return err
        }
        connected.set(true)
        return null
    }

    /**
     * Reconnect after connection loss. Call when [isConnected] is false and session is still [isRunning].
     * Returns true on success, false on failure.
     */
    fun tryReconnect(): Boolean {
        if (connected.get()) return true
        closeConnectionOnly()
        val err = doConnect()
        if (err != null) {
            AppLog.d(LOG_TAG) { "tryReconnect failed: $err" }
            return false
        }
        connected.set(true)
        AppLog.d(LOG_TAG) { "tryReconnect OK" }
        return true
    }

    /** Closes socket and reader; sets [connected] false. Does not set [running] false. */
    private fun closeConnectionOnly() {
        connected.set(false)
        try {
            socketRef.getAndSet(null)?.close()
        } catch (_: Exception) { }
        writerRef.set(null)
        readerThreadRef.getAndSet(null)?.interrupt()
    }

    /** Creates socket, reader thread, subscribe, authorize. Returns null on success, error message on failure. Does not set [running] or [connected]. */
    private fun doConnect(): String? {
        return try {
            AppLog.d(LOG_TAG) {
                if (useTls) "Connecting (TLS) $host:$port" else "Connecting (plain) $host:$port"
            }
            val socket = if (useTls) {
                val factory = if (stratumPin != null) {
                    val ctx = SSLContext.getInstance("TLS")
                    ctx.init(null, arrayOf<TrustManager>(stratumPinningTrustManager(stratumPin)), null)
                    ctx.socketFactory
                } else {
                    SSLSocketFactory.getDefault()
                }
                factory.createSocket(host, port) as Socket
            } else {
                Socket(host, port)
            }
            // Timeout so we detect dead/half-open connections and set connected=false for reconnect logic.
            socket.soTimeout = 90_000
            socketRef.set(socket)
            val writer = PrintWriter(socket.getOutputStream(), true)
            writerRef.set(writer)

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            readerThreadRef.set(Thread {
                try {
                    var line: String? = null
                    while (running.get()) {
                        line = reader.readLine()
                        if (line == null) break
                        handleLine(line)
                    }
                } catch (_: Exception) { }
                closeConnectionOnly()
                onConnectionLost?.invoke()
            }.apply { isDaemon = true; start() })

            authorizeResultRef.set(null)
            authorizeErrorRef.set(null)
            sendSubscribe()
            sendAuthorize()

            var waited = 0
            while (waited < 5000) {
                if (extranonce1Hex.get() != null && authorizeResultRef.get() != null) break
                Thread.sleep(50)
                waited += 50
            }
            if (extranonce1Hex.get() == null) {
                closeConnectionOnly()
                return "No subscribe response"
            }
            if (authorizeResultRef.get() == null) {
                closeConnectionOnly()
                return "No authorize response"
            }
            if (authorizeResultRef.get() == false) {
                closeConnectionOnly()
                return authorizeErrorRef.get() ?: "Authorization failed"
            }
            null
        } catch (e: Exception) {
            closeConnectionOnly()
            e.message ?: "Connection failed"
        }
    }

    fun disconnect() {
        running.set(false)
        closeConnectionOnly()
        currentJob.set(null)
    }

    private fun handleLine(line: String) {
        if (line.isBlank()) return
        try {
            val obj = JSONObject(line)
            if (obj.has("method")) {
                when (obj.optString("method")) {
                    "mining.notify" -> parseNotify(obj.optJSONArray("params"))
                    "mining.set_difficulty" -> parseSetDifficulty(obj.optJSONArray("params"))
                    "mining.set_extranonce" -> parseSetExtranonce(obj.optJSONArray("params"))
                    "client.reconnect" -> parseClientReconnect(obj.optJSONArray("params"))
                }
            } else if (obj.has("result")) {
                val id = (obj.opt("id") as? Number)?.toInt() ?: return@handleLine
                when (id) {
                    0 -> { /* mining.extranonce.subscribe response; ignore */ }
                    1 -> parseSubscribeResult(obj.opt("result"))
                    2 -> parseAuthorizeResult(obj.opt("result"), obj.opt("error"))
                    else -> parseSubmitResult(id, obj.opt("result"), obj.opt("error"))
                }
            }
        } catch (_: Exception) { }
    }

    private fun parseSubscribeResult(result: Any?) {
        if (result !is JSONArray || result.length() < 2) return
        extranonce1Hex.set(result.optString(1))
        if (result.length() >= 3) {
            extranonce2Size.set(result.optInt(2, 4))
        }
        AppLog.d(LOG_TAG) { "Subscribe OK" }
        sendExtranonceSubscribe()
    }

    private fun parseAuthorizeResult(result: Any?, error: Any?) {
        val accepted = result == true
        authorizeResultRef.set(accepted)
        if (accepted) {
            AppLog.d(LOG_TAG) { "Authorize OK" }
        } else {
            val msg = when (error) {
                is JSONArray -> error.optString(1, "Authorization failed")
                is String -> error
                else -> "Authorization failed"
            }
            authorizeErrorRef.set(msg)
            AppLog.e(LOG_TAG) { "Authorize failed: $msg" }
        }
    }

    private fun parseSubmitResult(id: Int, result: Any?, error: Any?) {
        val accepted = result == true
        val errorMessage = when {
            accepted -> null
            error is JSONArray -> error.optString(1, "Share rejected")
            error is String -> error
            else -> "Share rejected"
        }
        if (accepted) {
            AppLog.d(LOG_TAG) { "Submit result: accepted" }
        } else {
            AppLog.e(LOG_TAG) { "Submit result: rejected: ${errorMessage ?: "unknown"}" }
        }
        val callback = pendingSubmitCallbacks.remove(id)
        if (callback != null) {
            callback(accepted, errorMessage)
        } else {
            AppLog.e(LOG_TAG) { "Submit result for unknown id $id" }
        }
    }

    private fun parseNotify(params: JSONArray?) {
        if (params == null || params.length() < 9) return
        if (params.optBoolean(8, false)) {
            cleanJobsInvalidation.set(true)
        }
        val merkleList = params.optJSONArray(4) ?: JSONArray()
        val merkleBranch = (0 until merkleList.length()).map { merkleList.optString(it) }
        currentJob.set(StratumJob(
            jobId = params.optString(0),
            prevhashHex = params.optString(1),
            coinb1Hex = params.optString(2),
            coinb2Hex = params.optString(3),
            merkleBranchHex = merkleBranch,
            versionHex = params.optString(5),
            nbitsHex = params.optString(6),
            ntimeHex = params.optString(7),
            cleanJobs = params.optBoolean(8, false),
        ))
        onTemplateReceived?.invoke()
    }

    private fun parseSetExtranonce(params: JSONArray?) {
        if (params == null || params.length() < 1) return
        extranonce1Hex.set(params.optString(0))
        if (params.length() >= 2) {
            extranonce2Size.set(params.optInt(1, 4))
        }
        AppLog.d(LOG_TAG) { "Extranonce updated" }
    }

    private fun parseClientReconnect(params: JSONArray?) {
        if (params == null || params.length() < 2) return
        val newHost = params.optString(0)
        val newPort = params.optInt(1, 0)
        if (newHost.isBlank() || newPort !in 1..65535) return
        AppLog.d(LOG_TAG) { "client.reconnect to $newHost:$newPort" }
        onReconnectRequest?.invoke(newHost, newPort)
        disconnect()
    }

    private fun parseSetDifficulty(params: JSONArray?) {
        if (params != null && params.length() > 0) {
            currentDifficulty.set(params.optDouble(0, 1.0))
        }
    }

    private fun sendSubscribe() {
        val req = JSONObject().apply {
            put("id", 1)
            put("method", "mining.subscribe")
            put("params", JSONArray().put("btcminer-android/1.0"))
        }
        writerRef.get()?.println(req.toString())
    }

    private fun sendAuthorize() {
        val req = JSONObject().apply {
            put("id", 2)
            put("method", "mining.authorize")
            put("params", JSONArray().put(username).put(password))
        }
        writerRef.get()?.println(req.toString())
    }

    private fun sendExtranonceSubscribe() {
        val req = JSONObject().apply {
            put("id", 0)
            put("method", "mining.extranonce.subscribe")
            put("params", JSONArray())
        }
        writerRef.get()?.println(req.toString())
    }

    /**
     * Send mining.submit. Params: username, job_id, extranonce2 (hex), ntime (hex), nonce (hex).
     * onResult is invoked when the pool responds, keyed by request id.
     */
    fun sendSubmit(
        jobId: String,
        extranonce2Hex: String,
        ntimeHex: String,
        nonceHex: String,
        onResult: (accepted: Boolean, errorMessage: String?) -> Unit,
    ) {
        val id = requestId.incrementAndGet() // 3, 4, 5, ...
        pendingSubmitCallbacks[id] = onResult
        val req = JSONObject().apply {
            put("id", id)
            put("method", "mining.submit")
            put("params", JSONArray()
                .put(username)
                .put(jobId)
                .put(extranonce2Hex)
                .put(ntimeHex)
                .put(nonceHex))
        }
        writerRef.get()?.println(req.toString())
    }
}
