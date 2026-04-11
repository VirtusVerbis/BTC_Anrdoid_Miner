package com.btcminer.android.mining

import android.os.Process
import android.util.Base64
import com.btcminer.android.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.lang.reflect.Method

/**
 * Minimal Stratum v1 TCP client. Supports optional TLS.
 * Sends subscribe (id 1), authorize (id 2), submit (id 3+); parses notify, set_difficulty,
 * set_extranonce, client.reconnect, and RPC responses.
 *
 * Each logical share submit is tracked with a response timeout and bounded resubmits (new JSON-RPC id).
 * In-flight submits on connection loss are deferred to [reconnectSubmitQueue] and drained after reconnect.
 * On [disconnect], pending submits are finalized with "client stopped" and the scheduler is shut down.
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
    private val threadPriority: Int = 0,
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

    /** Maps current JSON-RPC submit id to in-flight logical submit. */
    private val pendingByRpcId = ConcurrentHashMap<Int, PendingLogicalSubmit>()

    /** Submits deferred when connection drops while [running] is still true. */
    private val reconnectSubmitQueue = ConcurrentLinkedQueue<DeferredSubmit>()

    private val requestId = AtomicInteger(2) // 1=subscribe, 2=authorize, 3+=submit

    private val submitScheduler = AtomicReference<ScheduledExecutorService?>(null)
    private val submitStateLock = Any()

    private data class DeferredSubmit(
        val jobId: String,
        val extranonce2Hex: String,
        val ntimeHex: String,
        val nonceHex: String,
        val onResultOnce: (Boolean, String?) -> Unit,
    )

    private class PendingLogicalSubmit(
        val jobId: String,
        val extranonce2Hex: String,
        val ntimeHex: String,
        val nonceHex: String,
        val onResultOnce: (Boolean, String?) -> Unit,
        var currentRpcId: Int,
        var attempt: Int,
        @Volatile var timeoutFuture: ScheduledFuture<*>?,
    )

    private companion object {
        private const val LOG_TAG = "Stratum"
        /** Connect timeout (ms) to avoid indefinite block when network is unavailable during reconnect. */
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val SUBMIT_RESPONSE_TIMEOUT_MS = 30_000L
        private const val MAX_SUBMIT_RETRIES = 5
        private const val MSG_CLIENT_STOPPED = "client stopped"
        private const val MSG_NO_RESPONSE = "no response after retries"

        private val sslSocketOverPlainMethod: Method = SSLSocketFactory::class.java.getDeclaredMethod(
            "createSocket",
            Socket::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        private fun createTlsSocketOverPlain(factory: SSLSocketFactory, plain: Socket, host: String, port: Int): Socket =
            sslSocketOverPlainMethod.invoke(factory, plain, host, port, true) as Socket

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

        private fun wrapOnce(user: (Boolean, String?) -> Unit): (Boolean, String?) -> Unit {
            val done = AtomicBoolean(false)
            return { accepted, msg ->
                if (done.compareAndSet(false, true)) {
                    user(accepted, msg)
                }
            }
        }
    }

    private fun ensureScheduler(): ScheduledExecutorService {
        submitScheduler.get()?.let { return it }
        val created = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "stratum-submit-timeout").apply { isDaemon = true }
        }
        if (submitScheduler.compareAndSet(null, created)) {
            return created
        }
        created.shutdown()
        return submitScheduler.get()!!
    }

    private fun shutdownSubmitScheduler() {
        submitScheduler.getAndSet(null)?.shutdownNow()
    }

    fun getCurrentJob(): StratumJob? = currentJob.get()
    fun getCurrentDifficulty(): Double = currentDifficulty.get()
    fun getExtranonce1Hex(): String? = extranonce1Hex.get()
    fun getExtranonce2Size(): Int = extranonce2Size.get()
    fun isRunning(): Boolean = running.get()
    /** True when socket is open and subscribe+authorize have succeeded. */
    fun isConnected(): Boolean = connected.get()

    /**
     * Count of Stratum-side submit work not yet finished: deferred reconnect queue plus in-flight JSON-RPC
     * submits awaiting a pool response ([pendingByRpcId] holds one entry per logical share during retries).
     */
    fun stratumInternalQueuedCount(): Int =
        reconnectSubmitQueue.size + pendingByRpcId.size

    /** Returns true if the last mining.notify had cleanJobs=true (caller should abort current job). */
    fun consumeCleanJobsInvalidation(): Boolean = cleanJobsInvalidation.getAndSet(false)

    /** True if the last mining.notify had cleanJobs=true. Does not clear the flag (use consumeCleanJobsInvalidation for that). */
    fun hasCleanJobsInvalidation(): Boolean = cleanJobsInvalidation.get()

    /**
     * After subscribe+authorize succeed, drain submits that were deferred during a prior connection loss.
     * Does not increment [com.btcminer.android.mining.NativeMiningEngine] identified share counts.
     */
    fun drainDeferredSubmitsAfterConnect() {
        while (true) {
            val d = reconnectSubmitQueue.poll() ?: break
            AppLog.d(LOG_TAG) { "Draining deferred submit jobId=${d.jobId}" }
            sendSubmitInternal(d.jobId, d.extranonce2Hex, d.ntimeHex, d.nonceHex, d.onResultOnce)
        }
    }

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
        drainDeferredSubmitsAfterConnect()
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
        drainDeferredSubmitsAfterConnect()
        return true
    }

    /** Closes socket and reader; sets [connected] false. Does not set [running] false. */
    private fun closeConnectionOnly() {
        connected.set(false)
        synchronized(submitStateLock) {
            val seen = HashSet<PendingLogicalSubmit>()
            pendingByRpcId.forEach { (_, p) -> seen.add(p) }
            pendingByRpcId.clear()
            for (p in seen) {
                p.timeoutFuture?.cancel(false)
                p.timeoutFuture = null
                if (running.get()) {
                    reconnectSubmitQueue.offer(
                        DeferredSubmit(p.jobId, p.extranonce2Hex, p.ntimeHex, p.nonceHex, p.onResultOnce),
                    )
                    AppLog.d(LOG_TAG) { "Deferred submit for reconnect jobId=${p.jobId} rpcId=${p.currentRpcId}" }
                } else {
                    p.onResultOnce(false, MSG_CLIENT_STOPPED)
                }
            }
        }
        try {
            socketRef.getAndSet(null)?.close()
        } catch (_: Exception) { }
        writerRef.set(null)
        readerThreadRef.getAndSet(null)?.interrupt()
    }

    /** Creates socket, reader thread, subscribe, authorize. Returns null on success, error message on failure. Does not set [running] or [connected]. */
    private fun doConnect(): String? {
        val connectPort = port.coerceIn(1, 65535)
        return try {
            AppLog.d(LOG_TAG) {
                if (useTls) "Connecting (TLS) $host:$connectPort" else "Connecting (plain) $host:$connectPort"
            }
            val socket = if (useTls) {
                val factory: SSLSocketFactory = if (stratumPin != null) {
                    val ctx = SSLContext.getInstance("TLS")
                    ctx.init(null, arrayOf<TrustManager>(stratumPinningTrustManager(stratumPin)), null)
                    ctx.socketFactory as SSLSocketFactory
                } else {
                    SSLSocketFactory.getDefault() as SSLSocketFactory
                }
                val plain = Socket()
                try {
                    plain.connect(InetSocketAddress(host, connectPort), CONNECT_TIMEOUT_MS)
                    createTlsSocketOverPlain(factory, plain, host, connectPort)
                } catch (e: Exception) {
                    try {
                        plain.close()
                    } catch (_: Exception) { }
                    throw e
                }
            } else {
                Socket().apply { connect(InetSocketAddress(host, connectPort), CONNECT_TIMEOUT_MS) }
            }
            socket.soTimeout = 90_000
            socketRef.set(socket)
            val writer = PrintWriter(socket.getOutputStream(), true)
            writerRef.set(writer)

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            readerThreadRef.set(Thread {
                try {
                    Process.setThreadPriority(threadPriority)
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
        synchronized(submitStateLock) {
            while (true) {
                val d = reconnectSubmitQueue.poll() ?: break
                d.onResultOnce(false, MSG_CLIENT_STOPPED)
            }
        }
        currentJob.set(null)
        shutdownSubmitScheduler()
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
        val pending: PendingLogicalSubmit?
        synchronized(submitStateLock) {
            pending = pendingByRpcId.remove(id)
            pending?.timeoutFuture?.cancel(false)
            pending?.timeoutFuture = null
        }
        if (pending == null) {
            AppLog.e(LOG_TAG) { "Submit result for unknown id $id" }
            return
        }
        pending.onResultOnce(accepted, errorMessage)
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

    private fun onSubmitResponseTimeout(expectedRpcId: Int) {
        var pending: PendingLogicalSubmit? = null
        synchronized(submitStateLock) {
            val p = pendingByRpcId[expectedRpcId] ?: return
            if (p.currentRpcId != expectedRpcId) return
            p.timeoutFuture?.cancel(false)
            p.timeoutFuture = null
            if (pendingByRpcId.remove(expectedRpcId, p)) {
                pending = p
            }
        }
        val p = pending ?: return

        val writer = writerRef.get()
        val stillRunning = running.get()
        val isConnected = connected.get() && writer != null

        if (p.attempt >= MAX_SUBMIT_RETRIES || !isConnected || !stillRunning) {
            val msg = when {
                !stillRunning -> MSG_CLIENT_STOPPED
                !isConnected -> "disconnected"
                else -> MSG_NO_RESPONSE
            }
            p.onResultOnce(false, msg)
            return
        }

        p.attempt += 1
        val newId = requestId.incrementAndGet()
        p.currentRpcId = newId
        val req = JSONObject().apply {
            put("id", newId)
            put("method", "mining.submit")
            put("params", JSONArray()
                .put(username)
                .put(p.jobId)
                .put(p.extranonce2Hex)
                .put(p.ntimeHex)
                .put(p.nonceHex))
        }
        synchronized(submitStateLock) {
            if (!connected.get() || writerRef.get() == null) {
                reconnectSubmitQueue.offer(
                    DeferredSubmit(p.jobId, p.extranonce2Hex, p.ntimeHex, p.nonceHex, p.onResultOnce),
                )
                return
            }
            pendingByRpcId[newId] = p
            p.timeoutFuture = scheduleSubmitTimeoutLocked(newId)
        }
        writer!!.println(req.toString())
        AppLog.d(LOG_TAG) { "Resubmit attempt ${p.attempt}/$MAX_SUBMIT_RETRIES id=$newId jobId=${p.jobId}" }
    }

    private fun scheduleSubmitTimeoutLocked(rpcId: Int): ScheduledFuture<*> {
        val exec = ensureScheduler()
        return exec.schedule({
            onSubmitResponseTimeout(rpcId)
        }, SUBMIT_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Send mining.submit. Params: username, job_id, extranonce2 (hex), ntime (hex), nonce (hex).
     * onResult is invoked at most once when the pool responds, retries exhaust, or the client stops.
     */
    fun sendSubmit(
        jobId: String,
        extranonce2Hex: String,
        ntimeHex: String,
        nonceHex: String,
        onResult: (accepted: Boolean, errorMessage: String?) -> Unit,
    ) {
        sendSubmitInternal(jobId, extranonce2Hex, ntimeHex, nonceHex, wrapOnce(onResult))
    }

    private fun sendSubmitInternal(
        jobId: String,
        extranonce2Hex: String,
        ntimeHex: String,
        nonceHex: String,
        onResultOnce: (Boolean, String?) -> Unit,
    ) {
        val writer = writerRef.get()
        if (writer == null || !connected.get()) {
            onResultOnce(false, "not connected")
            return
        }
        val id = requestId.incrementAndGet()
        val pending = PendingLogicalSubmit(
            jobId = jobId,
            extranonce2Hex = extranonce2Hex,
            ntimeHex = ntimeHex,
            nonceHex = nonceHex,
            onResultOnce = onResultOnce,
            currentRpcId = id,
            attempt = 1,
            timeoutFuture = null,
        )
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
        synchronized(submitStateLock) {
            if (!connected.get() || writerRef.get() == null) {
                onResultOnce(false, "not connected")
                return
            }
            pendingByRpcId[id] = pending
            pending.timeoutFuture = scheduleSubmitTimeoutLocked(id)
        }
        writer.println(req.toString())
    }
}
