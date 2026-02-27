package com.btcminer.android.mining

import android.os.Process
import com.btcminer.android.AppLog
import com.btcminer.android.config.MiningConfig
import java.util.Locale
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Mining engine using native hashing (Phase 2) and Kotlin Stratum client (Phase 3).
 * Implements [MiningEngine]; used when Option A1 is active.
 * Pool redirect (client.reconnect) is disabled: when the pool sends it, [onPoolRedirectRequested] is invoked for notification only; the app does not reconnect.
 */
class NativeMiningEngine(
    private val throttleStateRef: AtomicReference<ThrottleState>? = null,
    private val onPoolRedirectRequested: ((host: String, port: Int) -> Unit)? = null,
    private val getStratumPin: (String) -> String? = { null },
    private val onPinVerified: (() -> Unit)? = null,
    private val pendingSharesRepository: PendingSharesRepository? = null,
    private val isBothWifiAndDataUnavailable: (() -> Boolean)? = null,
    private val statsLogExtra: (() -> String)? = null,
    private val onGpuUnavailable: (() -> Unit)? = null,
) : MiningEngine {

    private companion object {
        private const val LOG_TAG = "Mining"
        /** Returned by gpuScanNonces when GPU path is unavailable (no CPU fallback). */
        private const val GPU_UNAVAILABLE = -2
        private const val CHUNK_SIZE = 2L * 1024 * 1024
        private const val MAX_NONCE = 0xFFFFFFFFL
        /** Minimum elapsed time (seconds) used as divisor for hashrate. Avoids a huge spike when "Start Mining" is clicked: dividing by a tiny elapsed time would show an inflated rate until the denominator grows. */
        private const val MIN_ELAPSED_SEC_FOR_HASHRATE = 1.0
        /** Rolling window (seconds) for hashrate display; configurable constant. */
        private const val ROLLING_WINDOW_SEC = 60
        /** Interval between GPU re-init attempts when GPU is unavailable. */
        private const val GPU_RETRY_INTERVAL_MS = 60_000L
    }

    private data class FoundResult(val jobId: String, val nonce: Int, val extranonce2Hex: String, val ntimeHex: String)

    private val running = AtomicBoolean(false)
    private val statusRef = AtomicReference(MiningStatus(MiningStatus.State.Idle))
    private val clientRef = AtomicReference<StratumClient?>(null)
    private val minerThreadRef = AtomicReference<Thread?>(null)
    private val extranonce2Counter = AtomicLong(0)
    private val acceptedShares = AtomicLong(0)
    private val rejectedShares = AtomicLong(0)
    private val identifiedShares = AtomicLong(0)
    private val totalNoncesScanned = AtomicLong(0)
    private val bestDifficultyRef = AtomicReference(0.0)
    private val blockTemplatesCount = AtomicLong(0)
    private val gpuNoncesScanned = AtomicLong(0)
    private val gpuUnavailable = AtomicBoolean(false)
    /** Background thread that periodically retries GPU init while GPU is unavailable. */
    private val gpuRetryThreadRunning = AtomicBoolean(false)
    @Volatile
    private var gpuRetryThread: Thread? = null

    /** Samples (timestampMs, cpuNonces, gpuNonces) for rolling-window hashrate. Cleared when mining loop starts. */
    private val hashrateSamples = Collections.synchronizedList(mutableListOf<Triple<Long, Long, Long>>())

    /**
     * Adds a sample and returns rolling (cpuHs, gpuHs) if the window has at least 2 samples and time span >= 1s; otherwise null (caller uses session average).
     */
    private fun addSampleAndGetRollingHashrate(nowMs: Long, cpuNonces: Long, gpuNonces: Long): Pair<Double, Double>? {
        synchronized(hashrateSamples) {
            hashrateSamples.add(Triple(nowMs, cpuNonces, gpuNonces))
            val cutoff = nowMs - ROLLING_WINDOW_SEC * 1000L
            hashrateSamples.removeAll { it.first < cutoff }
            if (hashrateSamples.size < 2) return null
            val old = hashrateSamples.first()
            val new = hashrateSamples.last()
            val dt = new.first - old.first
            if (dt < 1000L) return null
            val cpuHs = (new.second - old.second) * 1000.0 / dt
            val gpuHs = (new.third - old.third) * 1000.0 / dt
            return Pair(cpuHs, gpuHs)
        }
    }

    override fun start(config: MiningConfig) {
        if (running.getAndSet(true)) return
        AppLog.d(LOG_TAG) { "start()" }
        totalNoncesScanned.set(0)
        // Persistent counters (acceptedShares, rejectedShares, identifiedShares, bestDifficultyRef, blockTemplatesCount) are not reset here; nonces are per-round only

        val urlTrimmed = config.stratumUrl.trim()
        val host = urlTrimmed
            .removePrefix("stratum+tcp://").removePrefix("stratum+ssl://").removePrefix("tcp://")
            .split("/").first().trim()
        if (host.isBlank()) {
            val msg = "Invalid pool URL"
            AppLog.e(LOG_TAG) { "Error: $msg" }
            statusRef.set(MiningStatus(MiningStatus.State.Error, lastError = msg))
            running.set(false)
            return
        }
        val port = config.stratumPort.coerceIn(1, 65535)
        val username = config.stratumUser.trim()
        val password = config.stratumPass

        statusRef.set(MiningStatus(MiningStatus.State.Connecting))
        val useTls = config.stratumUrl.trim().lowercase().contains("ssl") || config.stratumPort == 443
        val stratumPin = getStratumPin(host)

        // First connect on this thread so the service sees correct isRunning() when start() returns.
        // Pool redirect (client.reconnect) is disabled: we only notify via onPoolRedirectRequested, do not reconnect.
        val client = StratumClient(host, port, username, password, useTls = useTls, stratumPin = stratumPin,
            onReconnectRequest = { h, p -> onPoolRedirectRequested?.invoke(h, p) },
            onTemplateReceived = { blockTemplatesCount.incrementAndGet() },
            onConnectionLost = null,
            threadPriority = config.miningThreadPriority)
        val err = client.connect()
        if (err != null) {
            AppLog.e(LOG_TAG) { "Connect failed: $err" }
            statusRef.set(MiningStatus(MiningStatus.State.Error, lastError = err))
            running.set(false)
            return
        }
        if (stratumPin != null) {
            onPinVerified?.invoke()
        }
        AppLog.d(LOG_TAG) { "Connected, flushing pending shares then starting mining" }
        flushPendingShares(client)
        clientRef.set(client)
        statusRef.set(MiningStatus(
            MiningStatus.State.Mining,
            hashrateHs = 0.0,
            gpuHashrateHs = 0.0,
            noncesScanned = totalNoncesScanned.get(),
            acceptedShares = acceptedShares.get(),
            rejectedShares = rejectedShares.get(),
            identifiedShares = identifiedShares.get(),
            bestDifficulty = bestDifficultyRef.get(),
            blockTemplates = blockTemplatesCount.get(),
        ))

        val minerThread = Thread {
            Process.setThreadPriority(config.miningThreadPriority)
            try {
                runMiningLoop(client, config)
            } catch (_: InterruptedException) { }
            finally {
                client.disconnect()
                clientRef.set(null)
            }
            // Pool redirect is disabled: we do not reconnect to a new host when the pool sends client.reconnect.
            running.set(false)
            statusRef.set(MiningStatus(
                MiningStatus.State.Idle,
                gpuHashrateHs = 0.0,
                acceptedShares = acceptedShares.get(),
                rejectedShares = rejectedShares.get(),
                identifiedShares = identifiedShares.get(),
                bestDifficulty = bestDifficultyRef.get(),
                blockTemplates = blockTemplatesCount.get(),
            ))
        }.apply { isDaemon = true; start() }
        minerThreadRef.set(minerThread)
    }

    override fun stop() {
        running.set(false)
        clientRef.getAndSet(null)?.disconnect()
        minerThreadRef.getAndSet(null)?.interrupt()
        // Stop GPU retry thread if running.
        gpuRetryThreadRunning.set(false)
        gpuRetryThread?.interrupt()
        gpuRetryThread = null
        statusRef.set(MiningStatus(
            MiningStatus.State.Idle,
            gpuHashrateHs = 0.0,
            acceptedShares = acceptedShares.get(),
            rejectedShares = rejectedShares.get(),
            identifiedShares = identifiedShares.get(),
            bestDifficulty = bestDifficultyRef.get(),
            blockTemplates = blockTemplatesCount.get(),
        ))
    }

    override fun resetAllCounters() {
        acceptedShares.set(0)
        rejectedShares.set(0)
        identifiedShares.set(0)
        totalNoncesScanned.set(0)
        bestDifficultyRef.set(0.0)
        blockTemplatesCount.set(0)
    }

    /**
     * Loads persisted counter values from disk (used by [MiningForegroundService] on first engine use).
     */
    fun loadPersistedStats(status: MiningStatus) {
        totalNoncesScanned.set(status.noncesScanned)
        acceptedShares.set(status.acceptedShares)
        rejectedShares.set(status.rejectedShares)
        identifiedShares.set(status.identifiedShares)
        bestDifficultyRef.set(status.bestDifficulty)
        blockTemplatesCount.set(status.blockTemplates)
        statusRef.set(status)
    }

    override fun isRunning(): Boolean = running.get()

    override fun getStatus(): MiningStatus = statusRef.get()

    private fun flushPendingShares(client: StratumClient) {
        val repo = pendingSharesRepository ?: return
        val list = repo.getAll()
        if (list.isEmpty()) return
        AppLog.d(LOG_TAG) { "Flushing ${list.size} pending share(s)" }
        for (share in list) {
            val latch = CountDownLatch(1)
            client.sendSubmit(share.jobId, share.extranonce2Hex, share.ntimeHex, share.nonceHex) { accepted, _ ->
                if (accepted) acceptedShares.incrementAndGet() else rejectedShares.incrementAndGet()
                latch.countDown()
            }
            latch.await(10, TimeUnit.SECONDS)
        }
        repo.clear()
    }

    private fun runMiningLoop(client: StratumClient, config: MiningConfig) {
        val statsStartTime = System.currentTimeMillis()
        gpuNoncesScanned.set(0)
        gpuUnavailable.set(false)
        synchronized(hashrateSamples) { hashrateSamples.clear() }
        var lastLogTime = statsStartTime
        val statusUpdateIntervalMs = config.statusUpdateIntervalMs.coerceIn(MiningConfig.STATUS_UPDATE_INTERVAL_MIN, MiningConfig.STATUS_UPDATE_INTERVAL_MAX)
        val threadCount = config.maxWorkerThreads.coerceIn(1, Runtime.getRuntime().availableProcessors())
        var gpuEnabled = config.gpuCores > 0 && NativeMiner.gpuIsAvailable() && !gpuUnavailable.get()
        if (gpuEnabled && !NativeMiner.gpuPipelineReady(config.gpuCores.coerceIn(MiningConfig.GPU_CORES_MIN, MiningConfig.GPU_CORES_MAX))) {
            if (!gpuUnavailable.getAndSet(true)) {
                onGpuUnavailable?.invoke()
                startGpuRetryThreadIfNeeded(config)
            }
            gpuEnabled = false
        }
        AppLog.d(LOG_TAG) { "Using $threadCount CPU worker(s), GPU=$gpuEnabled" }

        var cachedJob: StratumJob? = null
        var cachedDifficulty = 0.0
        var cachedEn1Hex: String? = null
        var cachedEn2Size = 4
        var lastReconnectAttemptMs = 0L

        while (running.get()) {
            val throttle = throttleStateRef?.get()
            if (throttle?.stopDueToOverheat == true) {
                AppLog.d(LOG_TAG) { "Stopping due to battery overheat" }
                statusRef.set(MiningStatus(MiningStatus.State.Idle, gpuHashrateHs = 0.0,
                    acceptedShares = acceptedShares.get(), rejectedShares = rejectedShares.get(),
                    identifiedShares = identifiedShares.get(), bestDifficulty = bestDifficultyRef.get(),
                    blockTemplates = blockTemplatesCount.get(), noncesScanned = totalNoncesScanned.get()))
                return
            }

            val job: StratumJob
            val difficulty: Double
            val en1Hex: String
            val en2Size: Int
            if (client.isConnected()) {
                // Update status immediately so UI (e.g. Stratum icon) shows connected even while waiting for a job.
                val nowConnected = System.currentTimeMillis()
                val elapsedSec = (nowConnected - statsStartTime) / 1000.0
                val effectiveElapsed = maxOf(elapsedSec, MIN_ELAPSED_SEC_FOR_HASHRATE)
                val cpuN = totalNoncesScanned.get()
                val gpuN = gpuNoncesScanned.get()
                val rolling = addSampleAndGetRollingHashrate(nowConnected, cpuN, gpuN)
                val hashrateHs = rolling?.first ?: (cpuN / effectiveElapsed)
                val gpuHashrateHs = rolling?.second ?: (gpuN / effectiveElapsed)
                statusRef.set(MiningStatus(
                    state = MiningStatus.State.Mining,
                    hashrateHs = hashrateHs,
                    gpuHashrateHs = gpuHashrateHs,
                    gpuAvailable = !gpuUnavailable.get(),
                    noncesScanned = cpuN,
                    acceptedShares = acceptedShares.get(),
                    rejectedShares = rejectedShares.get(),
                    identifiedShares = identifiedShares.get(),
                    bestDifficulty = bestDifficultyRef.get(),
                    blockTemplates = blockTemplatesCount.get(),
                    connectionLost = false,
                ))
                var j = client.getCurrentJob()
                while (j == null && running.get()) {
                    Thread.sleep(200)
                    j = client.getCurrentJob()
                }
                if (j == null) continue
                val diff = client.getCurrentDifficulty()
                if (diff <= 0.0) {
                    Thread.sleep(200)
                    continue
                }
                val en1 = client.getExtranonce1Hex() ?: continue
                val en2 = client.getExtranonce2Size().coerceAtLeast(4)
                cachedJob = j
                cachedDifficulty = diff
                cachedEn1Hex = en1
                cachedEn2Size = en2
                job = j
                difficulty = diff
                en1Hex = en1
                en2Size = en2
            } else {
                // Disconnected: attempt reconnect when we have network and delay has passed (even if we have a cached job).
                val now = System.currentTimeMillis()
                val bothUnavailable = isBothWifiAndDataUnavailable?.invoke() == true
                val shouldRetry = !bothUnavailable
                val delayMs = MiningConstants.STRATUM_RECONNECT_RETRY_DELAY_SEC * 1000L
                val delayPassed = now - lastReconnectAttemptMs >= delayMs
                if (!shouldRetry) {
                    AppLog.d(LOG_TAG) { "Disconnected: no network (WiFi and data unavailable), skipping tryReconnect" }
                } else if (!delayPassed) {
                    // Delay not passed; will retry when interval has elapsed.
                } else {
                    lastReconnectAttemptMs = now
                    AppLog.d(LOG_TAG) { "Disconnected: calling tryReconnect()" }
                    if (client.tryReconnect()) {
                        flushPendingShares(client)
                        continue
                    }
                }
                if (cachedJob == null || cachedEn1Hex == null) {
                    Thread.sleep(200)
                    val eff = maxOf((now - statsStartTime) / 1000.0, MIN_ELAPSED_SEC_FOR_HASHRATE)
                    val cpuN = totalNoncesScanned.get()
                    val gpuN = gpuNoncesScanned.get()
                    val rolling = addSampleAndGetRollingHashrate(now, cpuN, gpuN)
                    val hashrateHs = rolling?.first ?: (cpuN / eff)
                    val gpuHashrateHs = rolling?.second ?: (gpuN / eff)
                    statusRef.set(MiningStatus(
                        state = MiningStatus.State.Mining,
                        hashrateHs = hashrateHs,
                        gpuHashrateHs = gpuHashrateHs,
                        gpuAvailable = !gpuUnavailable.get(),
                        noncesScanned = cpuN,
                        acceptedShares = acceptedShares.get(),
                        rejectedShares = rejectedShares.get(),
                        identifiedShares = identifiedShares.get(),
                        bestDifficulty = bestDifficultyRef.get(),
                        blockTemplates = blockTemplatesCount.get(),
                        connectionLost = true,
                    ))
                    continue
                }
                job = cachedJob!!
                difficulty = cachedDifficulty
                en1Hex = cachedEn1Hex!!
                en2Size = cachedEn2Size
            }

            val isOfflineRound = !client.isConnected()  // mining with cached job while disconnected; do not break worker loop on connection check
            val extranonce2Hex = String.format("%0${en2Size * 2}x", extranonce2Counter.getAndIncrement() and 0xFFFFFFFFL)

            val merkleRoot = StratumHeaderBuilder.buildMerkleRoot(
                job.coinb1Hex,
                job.coinb2Hex,
                en1Hex,
                extranonce2Hex,
                job.merkleBranchHex,
            )
            val header76 = StratumHeaderBuilder.buildHeader76(job, merkleRoot)
            val target = StratumHeaderBuilder.buildTargetFromDifficulty(difficulty)
            val ntimeHex = job.ntimeHex

            var foundNonce = -1
            val activeJobId = AtomicReference(job.jobId)
            val foundRef = AtomicReference<FoundResult?>(null)
            val nextChunkStart = AtomicLong(0)
            if (threadCount == 1) {
                var lastStatusUpdateTime = statsStartTime
                val cpuWorker = Thread {
                    Process.setThreadPriority(config.miningThreadPriority)
                    val workerJobId = job.jobId
                    while (running.get() && foundRef.get() == null && activeJobId.get() == workerJobId) {
                        if (throttleStateRef?.get()?.stopDueToOverheat == true) break
                        if (client.isConnected() && (client.getCurrentJob()?.jobId != job.jobId || client.consumeCleanJobsInvalidation())) break
                        val throttle = throttleStateRef?.get()
                        val start = nextChunkStart.getAndAdd(CHUNK_SIZE)
                        if (start > MAX_NONCE) break
                        val nonceEndL = minOf(start + CHUNK_SIZE - 1, MAX_NONCE)
                        val nonceEnd = nonceEndL.toInt()
                        val n = NativeMiner.nativeScanNonces(header76, start.toInt(), nonceEnd, target)
                        val scanned = if (n >= 0) (n.toLong() - start + 1) else (nonceEndL - start + 1)
                        totalNoncesScanned.addAndGet(scanned)
                        val sleepMs = throttle?.throttleSleepMs ?: 0L
                        if (sleepMs > 0L) {
                            Thread.sleep(sleepMs)
                        } else {
                            val intensity = throttle?.effectiveIntensityPercent ?: config.maxIntensityPercent
                            if (intensity < 100) {
                                Thread.sleep((100 - intensity) * 20L / 100)
                            }
                        }
                        if (n >= 0) {
                            foundRef.compareAndSet(null, FoundResult(job.jobId, n, extranonce2Hex, ntimeHex))
                            break
                        }
                    }
                }.apply { isDaemon = true }
                val gpuWorkerSt = if (gpuEnabled) {
                    Thread {
                        Process.setThreadPriority(config.miningThreadPriority)
                        val workerJobId = job.jobId
                        while (running.get() && foundRef.get() == null && activeJobId.get() == workerJobId) {
                            if (throttleStateRef?.get()?.stopDueToOverheat == true) break
                            val throttle = throttleStateRef?.get()
                            val sleepMs = throttle?.throttleSleepMs ?: 0L
                            if (sleepMs > 0L) {
                                Thread.sleep(sleepMs)
                            } else {
                                val gpuUtil = (throttle?.effectiveGpuUtilizationPercent ?: config.gpuUtilizationPercent).coerceIn(MiningConfig.GPU_UTILIZATION_MIN, MiningConfig.GPU_UTILIZATION_MAX)
                                if (gpuUtil < 100) {
                                    Thread.sleep((100 - gpuUtil) * 20L / 100)
                                }
                            }
                            val start = nextChunkStart.getAndAdd(CHUNK_SIZE)
                            if (start > MAX_NONCE) break
                            val nonceEndL = minOf(start + CHUNK_SIZE - 1, MAX_NONCE)
                            val nonceEnd = nonceEndL.toInt()
                            val n = NativeMiner.gpuScanNonces(
                                header76,
                                start.toInt(),
                                nonceEnd,
                                target,
                                config.gpuCores.coerceIn(MiningConfig.GPU_CORES_MIN, MiningConfig.GPU_CORES_MAX)
                            )
                            if (n == GPU_UNAVAILABLE) {
                                if (!gpuUnavailable.getAndSet(true)) {
                                    onGpuUnavailable?.invoke()
                                    startGpuRetryThreadIfNeeded(config)
                                }
                                break
                            }
                            val scanned = if (n >= 0) (n.toLong() - start + 1) else (nonceEndL - start + 1)
                            gpuNoncesScanned.addAndGet(scanned)
                            if (n >= 0) {
                                foundRef.compareAndSet(null, FoundResult(job.jobId, n, extranonce2Hex, ntimeHex))
                                break
                            }
                        }
                    }.apply { isDaemon = true }
                } else null
                val singleWorkers = listOf(cpuWorker) + listOfNotNull(gpuWorkerSt)
                singleWorkers.forEach { it.start() }
                while (singleWorkers.any { it.isAlive }) {
                    if (!isOfflineRound && !client.isConnected()) {
                        AppLog.d(LOG_TAG) { "Connection lost during mining, breaking out to try reconnect" }
                        activeJobId.set(null)
                        break
                    }
                    if (client.isConnected() && (client.getCurrentJob()?.jobId != activeJobId.get() || client.consumeCleanJobsInvalidation())) {
                        AppLog.d(LOG_TAG) { "Job changed, switching to new template" }
                        activeJobId.set(null)
                    }
                    singleWorkers.forEach { it.join(statusUpdateIntervalMs.toLong()) }
                    val now = System.currentTimeMillis()
                    if (isOfflineRound) {
                        val bothUnavailable = isBothWifiAndDataUnavailable?.invoke() == true
                        val delayMs = MiningConstants.STRATUM_RECONNECT_RETRY_DELAY_SEC * 1000L
                        if (!bothUnavailable && (now - lastReconnectAttemptMs >= delayMs)) {
                            lastReconnectAttemptMs = now
                            if (client.tryReconnect()) {
                                AppLog.d(LOG_TAG) { "Reconnected from offline round (single-worker), flushing queue" }
                                flushPendingShares(client)
                                activeJobId.set(null)
                                break
                            }
                        }
                    }
                    if (now - lastStatusUpdateTime >= statusUpdateIntervalMs) {
                        val elapsedSec = (now - statsStartTime) / 1000.0
                        val effectiveElapsed = maxOf(elapsedSec, MIN_ELAPSED_SEC_FOR_HASHRATE)
                        val cpuN = totalNoncesScanned.get()
                        val gpuN = gpuNoncesScanned.get()
                        val rolling = addSampleAndGetRollingHashrate(now, cpuN, gpuN)
                        val hashrateHs = rolling?.first ?: (cpuN / effectiveElapsed)
                        val gpuHashrateHs = rolling?.second ?: (gpuN / effectiveElapsed)
                        statusRef.set(MiningStatus(
                            state = MiningStatus.State.Mining,
                            hashrateHs = hashrateHs,
                            gpuHashrateHs = gpuHashrateHs,
                            gpuAvailable = !gpuUnavailable.get(),
                            noncesScanned = cpuN,
                            acceptedShares = acceptedShares.get(),
                            rejectedShares = rejectedShares.get(),
                            identifiedShares = identifiedShares.get(),
                            bestDifficulty = bestDifficultyRef.get(),
                            blockTemplates = blockTemplatesCount.get(),
                            connectionLost = !client.isConnected(),
                        ))
                        lastStatusUpdateTime = now
                    }
                    if (now - lastLogTime >= AppLog.STATS_LOG_INTERVAL_MS) {
                        val elapsedSec = (now - statsStartTime) / 1000.0
                        val effectiveElapsed = maxOf(elapsedSec, MIN_ELAPSED_SEC_FOR_HASHRATE)
                        val hashrateHs = totalNoncesScanned.get() / effectiveElapsed
                        val gpuH = gpuNoncesScanned.get() / effectiveElapsed
                        AppLog.d(LOG_TAG) { String.format(Locale.US, "Stats: CPU %.2f GPU %.2f H/s, nonces=%d, blockTemplate=%d%s", hashrateHs, gpuH, totalNoncesScanned.get(), blockTemplatesCount.get(), statsLogExtra?.invoke() ?: "") }
                        lastLogTime = now
                    }
                }
                val found = foundRef.get()
                if (found != null) foundNonce = found.nonce
            } else {
                val cpuWorkers = (0 until threadCount).map {
                    val workerJobId = job.jobId
                    Thread {
                        Process.setThreadPriority(config.miningThreadPriority)
                        while (running.get() && foundRef.get() == null && activeJobId.get() == workerJobId) {
                            if (throttleStateRef?.get()?.stopDueToOverheat == true) break
                            val throttle = throttleStateRef?.get()
                            val start = nextChunkStart.getAndAdd(CHUNK_SIZE)
                            if (start > MAX_NONCE) break
                            val nonceEndL = minOf(start + CHUNK_SIZE - 1, MAX_NONCE)
                            val nonceEnd = nonceEndL.toInt()
                            val n = NativeMiner.nativeScanNonces(header76, start.toInt(), nonceEnd, target)
                            val scanned = if (n >= 0) (n.toLong() - start + 1) else (nonceEndL - start + 1)
                            totalNoncesScanned.addAndGet(scanned)
                            val sleepMs = throttle?.throttleSleepMs ?: 0L
                            if (sleepMs > 0L) {
                                Thread.sleep(sleepMs)
                            } else {
                                val intensity = throttle?.effectiveIntensityPercent ?: config.maxIntensityPercent
                                if (intensity < 100) {
                                    Thread.sleep((100 - intensity) * 20L / 100)
                                }
                            }
                            if (n >= 0) {
                                foundRef.compareAndSet(null, FoundResult(job.jobId, n, extranonce2Hex, ntimeHex))
                                break
                            }
                        }
                    }.apply { isDaemon = true }
                }
                val gpuWorker: Thread? = if (gpuEnabled) {
                    Thread {
                        Process.setThreadPriority(config.miningThreadPriority)
                        val workerJobId = job.jobId
                        while (running.get() && foundRef.get() == null && activeJobId.get() == workerJobId) {
                            if (throttleStateRef?.get()?.stopDueToOverheat == true) break
                            val throttle = throttleStateRef?.get()
                            val sleepMs = throttle?.throttleSleepMs ?: 0L
                            if (sleepMs > 0L) {
                                Thread.sleep(sleepMs)
                            } else {
                                val gpuUtil = (throttle?.effectiveGpuUtilizationPercent ?: config.gpuUtilizationPercent).coerceIn(MiningConfig.GPU_UTILIZATION_MIN, MiningConfig.GPU_UTILIZATION_MAX)
                                if (gpuUtil < 100) {
                                    Thread.sleep((100 - gpuUtil) * 20L / 100)
                                }
                            }
                            val start = nextChunkStart.getAndAdd(CHUNK_SIZE)
                            if (start > MAX_NONCE) break
                            val nonceEndL = minOf(start + CHUNK_SIZE - 1, MAX_NONCE)
                            val nonceEnd = nonceEndL.toInt()
                            val n = NativeMiner.gpuScanNonces(
                                header76,
                                start.toInt(),
                                nonceEnd,
                                target,
                                config.gpuCores.coerceIn(MiningConfig.GPU_CORES_MIN, MiningConfig.GPU_CORES_MAX)
                            )
                            if (n == GPU_UNAVAILABLE) {
                                if (!gpuUnavailable.getAndSet(true)) {
                                    onGpuUnavailable?.invoke()
                                    startGpuRetryThreadIfNeeded(config)
                                }
                                break
                            }
                            val scanned = if (n >= 0) (n.toLong() - start + 1) else (nonceEndL - start + 1)
                            gpuNoncesScanned.addAndGet(scanned)
                            if (n >= 0) {
                                foundRef.compareAndSet(null, FoundResult(job.jobId, n, extranonce2Hex, ntimeHex))
                                break
                            }
                        }
                    }.apply { isDaemon = true }
                } else null
                val allWorkers = cpuWorkers + listOfNotNull(gpuWorker)
                allWorkers.forEach { it.start() }
                while (allWorkers.any { it.isAlive }) {
                    if (!isOfflineRound && !client.isConnected()) {
                        AppLog.d(LOG_TAG) { "Connection lost during mining, breaking out to try reconnect" }
                        activeJobId.set(null)
                        break
                    }
                    if (client.isConnected() && (client.getCurrentJob()?.jobId != activeJobId.get() || client.consumeCleanJobsInvalidation())) {
                        AppLog.d(LOG_TAG) { "Job changed, switching to new template" }
                        activeJobId.set(null)
                    }
                    allWorkers.forEach { it.join(statusUpdateIntervalMs.toLong()) }
                    val now = System.currentTimeMillis()
                    if (isOfflineRound) {
                        val bothUnavailable = isBothWifiAndDataUnavailable?.invoke() == true
                        val delayMs = MiningConstants.STRATUM_RECONNECT_RETRY_DELAY_SEC * 1000L
                        if (!bothUnavailable && (now - lastReconnectAttemptMs >= delayMs)) {
                            lastReconnectAttemptMs = now
                            if (client.tryReconnect()) {
                                AppLog.d(LOG_TAG) { "Reconnected from offline round (multi-worker), flushing queue" }
                                flushPendingShares(client)
                                activeJobId.set(null)
                                break
                            }
                        }
                    }
                    val elapsedSec = (now - statsStartTime) / 1000.0
                    val effectiveElapsed = maxOf(elapsedSec, MIN_ELAPSED_SEC_FOR_HASHRATE)
                    val cpuN = totalNoncesScanned.get()
                    val gpuN = gpuNoncesScanned.get()
                    val rolling = addSampleAndGetRollingHashrate(now, cpuN, gpuN)
                    val hashrateHs = rolling?.first ?: (cpuN / effectiveElapsed)
                    val gpuHashrateHs = rolling?.second ?: (gpuN / effectiveElapsed)
                    statusRef.set(MiningStatus(
                        state = MiningStatus.State.Mining,
                        hashrateHs = hashrateHs,
                        gpuHashrateHs = gpuHashrateHs,
                        gpuAvailable = !gpuUnavailable.get(),
                        noncesScanned = cpuN,
                        acceptedShares = acceptedShares.get(),
                        rejectedShares = rejectedShares.get(),
                        identifiedShares = identifiedShares.get(),
                        bestDifficulty = bestDifficultyRef.get(),
                        blockTemplates = blockTemplatesCount.get(),
                        connectionLost = !client.isConnected(),
                    ))
                    if (now - lastLogTime >= AppLog.STATS_LOG_INTERVAL_MS) {
                        AppLog.d(LOG_TAG) { String.format(Locale.US, "Stats: CPU %.2f GPU %.2f H/s, nonces=%d, blockTemplate=%d%s", hashrateHs, gpuHashrateHs, totalNoncesScanned.get(), blockTemplatesCount.get(), statsLogExtra?.invoke() ?: "") }
                        lastLogTime = now
                    }
                }
                val found = foundRef.get()
                if (found != null) foundNonce = found.nonce
            }
            if (!running.get()) break
            if (foundNonce < 0) continue

            val header80 = StratumHeaderBuilder.header76WithNonce(header76, foundNonce)
            val diff = StratumHeaderBuilder.difficultyFromHeader80(header80)
            bestDifficultyRef.updateAndGet { maxOf(it, diff) }
            identifiedShares.incrementAndGet()
            val nonceHex = String.format("%08x", (foundNonce.toLong() and 0xFFFFFFFFL))
            if (client.isConnected()) {
                if (client.getCurrentJob()?.jobId != job.jobId) AppLog.d(LOG_TAG) { "Stale job, submitting anyway so pool sees we are alive" }
                AppLog.d(LOG_TAG) { String.format(Locale.US, "Share submitted (jobId=%s, nonce 0x%s)", job.jobId, nonceHex) }
                client.sendSubmit(job.jobId, extranonce2Hex, ntimeHex, nonceHex) { accepted, errorMessage ->
                    if (accepted) {
                        acceptedShares.incrementAndGet()
                        AppLog.d(LOG_TAG) { String.format(Locale.US, "Share accepted #%d", acceptedShares.get()) }
                    } else {
                        rejectedShares.incrementAndGet()
                        AppLog.e(LOG_TAG) { String.format(Locale.US, "Share rejected #%d: %s", rejectedShares.get(), errorMessage ?: "unknown") }
                    }
                }
            } else {
                pendingSharesRepository?.add(PendingSharesRepository.QueuedShare(job.jobId, extranonce2Hex, ntimeHex, nonceHex))
                AppLog.d(LOG_TAG) { "Queued share (disconnected)" }
            }
            val nowAfterShare = System.currentTimeMillis()
            val elapsedSec = (nowAfterShare - statsStartTime) / 1000.0
            val effectiveElapsed = maxOf(elapsedSec, MIN_ELAPSED_SEC_FOR_HASHRATE)
            val cpuN = totalNoncesScanned.get()
            val gpuN = gpuNoncesScanned.get()
            val rolling = addSampleAndGetRollingHashrate(nowAfterShare, cpuN, gpuN)
            val hashrateHs = rolling?.first ?: (cpuN / effectiveElapsed)
            val gpuHashrateHs = rolling?.second ?: (gpuN / effectiveElapsed)
            statusRef.set(MiningStatus(
                state = MiningStatus.State.Mining,
                hashrateHs = hashrateHs,
                gpuHashrateHs = gpuHashrateHs,
                gpuAvailable = !gpuUnavailable.get(),
                noncesScanned = cpuN,
                acceptedShares = acceptedShares.get(),
                rejectedShares = rejectedShares.get(),
                identifiedShares = identifiedShares.get(),
                bestDifficulty = bestDifficultyRef.get(),
                blockTemplates = blockTemplatesCount.get(),
                connectionLost = !client.isConnected(),
            ))
        }
    }

    /**
     * Starts a dedicated background thread that periodically retries GPU init while GPU is unavailable.
     * The thread sleeps [GPU_RETRY_INTERVAL_MS] between attempts and exits when mining stops, GPU becomes
     * available again, or a retry succeeds.
     */
    private fun startGpuRetryThreadIfNeeded(config: MiningConfig) {
        if (!gpuRetryThreadRunning.compareAndSet(false, true)) return
        val gpuCores = config.gpuCores.coerceIn(MiningConfig.GPU_CORES_MIN, MiningConfig.GPU_CORES_MAX)
        val thread = Thread({
            try {
                while (running.get() && gpuUnavailable.get()) {
                    try {
                        Thread.sleep(GPU_RETRY_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (!running.get() || !gpuUnavailable.get()) break
                    AppLog.d(LOG_TAG) { "GPU retry attempt starting" }
                    val available = NativeMiner.gpuIsAvailable() &&
                        NativeMiner.gpuPipelineReady(gpuCores)
                    if (available) {
                        gpuUnavailable.set(false)
                        AppLog.d(LOG_TAG) { "GPU init succeeded; resuming GPU mining" }
                        break
                    } else {
                        AppLog.d(LOG_TAG) {
                            "GPU init failed, will retry in ${GPU_RETRY_INTERVAL_MS / 1000}s"
                        }
                    }
                }
            } finally {
                gpuRetryThreadRunning.set(false)
            }
        }, "gpu-retry").apply {
            isDaemon = true
            start()
        }
        gpuRetryThread = thread
    }
}
