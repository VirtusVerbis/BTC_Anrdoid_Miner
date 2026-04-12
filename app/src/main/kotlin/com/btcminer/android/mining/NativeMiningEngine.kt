package com.btcminer.android.mining

import android.os.Process
import com.btcminer.android.AppLog
import com.btcminer.android.config.MiningConfig
import com.btcminer.android.network.StratumPinCapture
import com.btcminer.android.util.NumberFormatUtils
import java.util.Locale
import java.util.Collections
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
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

    companion object {
        private const val LOG_TAG = "Mining"
        /** Returned by gpuScanNonces when GPU path is unavailable (no CPU fallback). */
        private const val GPU_UNAVAILABLE = -2
        /** Returned by nativeScanNonces when CPU worker was interrupted by stuck watchdog. */
        private const val CPU_INTERRUPTED = -3
        /** Returned when native CPU SHA flavor path fails; same handling as [CPU_INTERRUPTED] in workers. */
        private const val CPU_SHA_FLAVOR_ERROR = NativeMiner.CPU_SHA_FLAVOR_ERROR

        /** [MiningStatus.lastError] marker for SHA self-test failure (dedicated Toast in service). */
        const val SHA256_SELFTEST_LAST_ERROR = "CPU_SHA256_SELFTEST"
        /** GPU host or Vulkan digest readback self-test failure. */
        const val GPU_SHA256_SELFTEST_LAST_ERROR = "GPU_SHA256_SELFTEST"
        private const val CHUNK_SIZE = 2L * 1024 * 1024
        private const val MAX_NONCE = 0xFFFFFFFFL
        /** CPU nonce range end; GPU uses CPU_NONCE_END to MAX_NONCE. */
        private const val CPU_NONCE_END = MAX_NONCE / 2
        /** Minimum elapsed time (seconds) used as divisor for hashrate. Avoids a huge spike when "Start Mining" is clicked: dividing by a tiny elapsed time would show an inflated rate until the denominator grows. */
        private const val MIN_ELAPSED_SEC_FOR_HASHRATE = 1.0
        /** Rolling window (seconds) for hashrate display; configurable constant. */
        private const val ROLLING_WINDOW_SEC = 60

        /** Fixed intensity sleep (ms per chunk). 0% = 10 min, 100% = 0. */
        private fun fixedIntensitySleepMs(intensityPercent: Int): Long =
            MiningConfig.intensityDelayMs(intensityPercent)
    }

    private data class FoundResult(val jobId: String, val nonce: Int, val extranonce2Hex: String, val ntimeHex: String, val header76: ByteArray)

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
    /** Max share difficulty observed in the current mining session (panel #1); not persisted as lifetime. */
    private val sessionBestShareDifficultyRef = AtomicReference(0.0)
    private val gpuNoncesScanned = AtomicLong(0)
    private val gpuUnavailable = AtomicBoolean(false)
    /** Background thread that periodically retries GPU init while GPU is unavailable. */
    private val gpuRetryThreadRunning = AtomicBoolean(false)
    @Volatile
    private var gpuRetryThread: Thread? = null

    /** Shared: when clean_jobs, set to null so both CPU and GPU workers exit. */
    private val activeJobId = AtomicReference<String?>(null)
    /** Found shares from CPU or GPU workers; coordinator drains and submits. */
    private val foundSharesQueue: BlockingQueue<FoundResult> = LinkedBlockingQueue()
    @Volatile
    private var cpuSupervisorThread: Thread? = null
    @Volatile
    private var gpuSupervisorThread: Thread? = null

    private val lastCpuIntensityDelayMs = AtomicLong(0L)
    private val lastGpuIntensityDelayMs = AtomicLong(0L)

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
        lastCpuIntensityDelayMs.set(0L)
        lastGpuIntensityDelayMs.set(0L)
        // Persistent counters (acceptedShares, rejectedShares, identifiedShares, bestDifficultyRef, blockTemplatesCount) are not reset here; nonces are per-round only

        val urlTrimmed = config.stratumUrl.trim()
        val host = StratumPinCapture.normalizeHost(urlTrimmed)
        if (host.isBlank()) {
            val msg = "Invalid pool URL"
            AppLog.e(LOG_TAG) { "Error: $msg" }
            statusRef.set(MiningStatus(MiningStatus.State.Error, lastError = msg, queuedShares = queuedSharesCount(null)))
            running.set(false)
            return
        }

        val flavor = config.cpuSha256Flavor
        if (!NativeMiner.nativeSelfTestCpuSha256Flavor(flavor.ordinal)) {
            AppLog.e(LOG_TAG) { "CPU SHA-256 self-test failed for flavor=${flavor.name}" }
            statusRef.set(MiningStatus(MiningStatus.State.Error, lastError = SHA256_SELFTEST_LAST_ERROR, queuedShares = queuedSharesCount(null)))
            running.set(false)
            return
        }
        AppLog.d(LOG_TAG) { "CPU SHA-256 flavor active: ${flavor.name}" }

        if (config.gpuCores > 0) {
            if (!NativeMiner.gpuShaHostSelftest()) {
                AppLog.e(LOG_TAG) { "GPU SHA-256 host self-test failed" }
                statusRef.set(MiningStatus(MiningStatus.State.Error, lastError = GPU_SHA256_SELFTEST_LAST_ERROR, queuedShares = queuedSharesCount(null)))
                running.set(false)
                return
            }
            if (NativeMiner.gpuIsAvailable()) {
                if (!NativeMiner.gpuShaVulkanSelftest(0)) {
                    AppLog.e(LOG_TAG) { "GPU SHA-256 Vulkan self-test failed (full path)" }
                    statusRef.set(MiningStatus(MiningStatus.State.Error, lastError = GPU_SHA256_SELFTEST_LAST_ERROR, queuedShares = queuedSharesCount(null)))
                    running.set(false)
                    return
                }
                if (!NativeMiner.gpuShaVulkanSelftest(1)) {
                    AppLog.e(LOG_TAG) { "GPU SHA-256 Vulkan self-test failed (midstate path)" }
                    statusRef.set(MiningStatus(MiningStatus.State.Error, lastError = GPU_SHA256_SELFTEST_LAST_ERROR, queuedShares = queuedSharesCount(null)))
                    running.set(false)
                    return
                }
            }
        }

        val username = config.stratumUser.trim()
        val password = config.stratumPass

        statusRef.set(MiningStatus(MiningStatus.State.Connecting, queuedShares = queuedSharesCount(null)))
        val port = config.stratumPort.coerceIn(1, 65535)
        val useTls = StratumPinCapture.indicatesTls(config.stratumUrl, port)
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
            statusRef.set(MiningStatus(MiningStatus.State.Error, lastError = err, queuedShares = queuedSharesCount(null)))
            running.set(false)
            return
        }
        if (stratumPin != null) {
            onPinVerified?.invoke()
        }
        AppLog.d(LOG_TAG) { "Connected, draining deferred submits then flushing pending shares" }
        client.drainDeferredSubmitsAfterConnect()
        flushPendingShares(client)
        clientRef.set(client)
        statusRef.set(MiningStatus(
            MiningStatus.State.Mining,
            hashrateHs = 0.0,
            gpuHashrateHs = 0.0,
            noncesScanned = totalNoncesScanned.get() + gpuNoncesScanned.get(),
            acceptedShares = acceptedShares.get(),
            rejectedShares = rejectedShares.get(),
            identifiedShares = identifiedShares.get(),
            queuedShares = queuedSharesCount(client),
            bestDifficulty = bestDifficultyRef.get(),
            blockTemplates = blockTemplatesCount.get(),
            stratumDifficulty = client.getCurrentDifficulty(),
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
                queuedShares = queuedSharesCount(null),
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
        cpuSupervisorThread?.interrupt()
        cpuSupervisorThread = null
        gpuSupervisorThread?.interrupt()
        gpuSupervisorThread = null
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
            queuedShares = queuedSharesCount(null),
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
        sessionBestShareDifficultyRef.set(0.0)
        syncResetCounterFieldsIntoStatusRef()
    }

    override fun getSessionBestShareDifficulty(): Double = sessionBestShareDifficultyRef.get()

    override fun resetSessionScopeDisplay() {
        sessionBestShareDifficultyRef.set(0.0)
    }

    /** Merges atomics/refs into [statusRef] so [getStatus] matches counters immediately after [resetAllCounters] (mid-session safe: preserves state, hashrates, Stratum fields). */
    private fun syncResetCounterFieldsIntoStatusRef() {
        val cur = statusRef.get()
        statusRef.set(
            cur.copy(
                acceptedShares = acceptedShares.get(),
                rejectedShares = rejectedShares.get(),
                identifiedShares = identifiedShares.get(),
                noncesScanned = totalNoncesScanned.get() + gpuNoncesScanned.get(),
                queuedShares = queuedSharesCount(clientRef.get()),
                bestDifficulty = bestDifficultyRef.get(),
                blockTemplates = blockTemplatesCount.get(),
            ),
        )
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
        sessionBestShareDifficultyRef.set(0.0)
        statusRef.set(status)
    }

    override fun isRunning(): Boolean = running.get()

    override fun getStatus(): MiningStatus = statusRef.get()

    override fun getCurrentStratumNbitsHex(): String? =
        clientRef.get()?.getCurrentJob()?.nbitsHex?.trim()?.takeIf { it.isNotEmpty() }

    /** Disk-backed pending shares plus Stratum reconnect queue and in-flight submit RPCs. */
    private fun queuedSharesCount(stratum: StratumClient?): Long {
        val repo = pendingSharesRepository?.getAll()?.size ?: 0
        val st = stratum?.stratumInternalQueuedCount() ?: 0
        return (repo + st).toLong()
    }

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
            latch.await(180, TimeUnit.SECONDS)
        }
        repo.clear()
    }

    private data class RoundContext(
        val job: StratumJob,
        val header76: ByteArray,
        val target: ByteArray,
        val ntimeHex: String,
        val extranonce2Hex: String,
        val isOfflineRound: Boolean,
    )

    private fun runCpuRound(
        client: StratumClient,
        config: MiningConfig,
        ctx: RoundContext,
        threadCount: Int,
        statusUpdateIntervalMs: Int,
        statsStartTime: Long,
    ) {
        val job = ctx.job
        activeJobId.set(job.jobId)
        val nextChunkStart = AtomicLong(0)
        val roundStartTimeMs = System.currentTimeMillis()

        val cpuWorkers = (0 until threadCount).map {
            val workerJobId = job.jobId
            Thread {
                Process.setThreadPriority(config.miningThreadPriority)
                while (running.get() && activeJobId.get() == workerJobId) {
                    if (throttleStateRef?.get()?.stopDueToOverheat == true) break
                    if (client.isConnected() && client.hasCleanJobsInvalidation()) break
                    val throttle = throttleStateRef?.get()
                    val start = nextChunkStart.getAndAdd(CHUNK_SIZE)
                    if (start > CPU_NONCE_END) break
                    val nonceEndL = minOf(start + CHUNK_SIZE - 1, CPU_NONCE_END)
                    val nonceEnd = nonceEndL.toInt()
                    val t0 = System.currentTimeMillis()
                    val n = NativeMiner.nativeScanNonces(
                        ctx.header76,
                        start.toInt(),
                        nonceEnd,
                        ctx.target,
                        config.cpuSha256Flavor.ordinal,
                    )
                    val workMs = System.currentTimeMillis() - t0
                    if (n == CPU_INTERRUPTED || n == CPU_SHA_FLAVOR_ERROR) {
                        if (n == CPU_SHA_FLAVOR_ERROR) {
                            AppLog.e(LOG_TAG) { "CPU SHA flavor error in worker (flavor=${config.cpuSha256Flavor.name})" }
                        } else {
                            AppLog.d(LOG_TAG) { "CPU worker interrupted (stuck watchdog)" }
                        }
                        break
                    }
                    val scanned = if (n >= 0) (n.toLong() - start + 1) else (nonceEndL - start + 1)
                    totalNoncesScanned.addAndGet(scanned)
                    val intensity = throttle?.effectiveIntensityPercent ?: config.maxIntensityPercent
                    val throttleSleep = throttle?.throttleSleepMs ?: 0L
                    val cpuIntensityDelay = fixedIntensitySleepMs(intensity)
                    lastCpuIntensityDelayMs.set(cpuIntensityDelay)
                    val totalSleep = cpuIntensityDelay + throttleSleep
                    if (totalSleep > 0L) {
                        try {
                            Thread.sleep(totalSleep)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    if (n >= 0) {
                        foundSharesQueue.offer(FoundResult(job.jobId, n, ctx.extranonce2Hex, ctx.ntimeHex, ctx.header76))
                        break
                    }
                }
            }.apply { isDaemon = true }
        }
        cpuWorkers.forEach { it.start() }
        while (cpuWorkers.any { it.isAlive }) {
            if (!ctx.isOfflineRound && !client.isConnected()) {
                AppLog.d(LOG_TAG) { "Connection lost during CPU mining, breaking out to try reconnect" }
                activeJobId.set(null)
                break
            }
            if (client.isConnected() && client.consumeCleanJobsInvalidation()) {
                AppLog.d(LOG_TAG) { "Job changed (clean_jobs), switching to new template" }
                activeJobId.set(null)
            }
            try {
                cpuWorkers.forEach { it.join(statusUpdateIntervalMs.toLong()) }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            val now = System.currentTimeMillis()
            val elapsed = now - roundStartTimeMs
            if (elapsed >= MiningConstants.ROUND_STUCK_TIMEOUT_MS && cpuWorkers.any { it.isAlive }) {
                AppLog.d(LOG_TAG) { "CPU round stuck (${elapsed / 1000}s), interrupting all workers" }
                NativeMiner.cpuRequestInterrupt()
                cpuWorkers.forEach { it.interrupt() }
                activeJobId.set(null)
            }
        }
    }

    private fun runGpuRound(
        client: StratumClient,
        config: MiningConfig,
        ctx: RoundContext,
        statusUpdateIntervalMs: Int,
        statsStartTime: Long,
    ) {
        val job = ctx.job
        activeJobId.set(job.jobId)
        val nextChunkStart = AtomicLong(CPU_NONCE_END)
        val roundStartTimeMs = System.currentTimeMillis()
        val roundStartGpuNonces = gpuNoncesScanned.get()

        val gpuWorker = Thread {
            Process.setThreadPriority(config.miningThreadPriority)
            val workerJobId = job.jobId
            while (running.get() && activeJobId.get() == workerJobId) {
                if (throttleStateRef?.get()?.stopDueToOverheat == true) break
                val throttle = throttleStateRef?.get()
                val start = nextChunkStart.getAndAdd(CHUNK_SIZE)
                if (start > MAX_NONCE) break
                val nonceEndL = minOf(start + CHUNK_SIZE - 1, MAX_NONCE)
                val nonceEnd = nonceEndL.toInt()
                val t0 = System.currentTimeMillis()
                val n = NativeMiner.gpuScanNonces(
                    ctx.header76,
                    start.toInt(),
                    nonceEnd,
                    ctx.target,
                    config.gpuCores.coerceIn(MiningConfig.GPU_CORES_MIN, MiningConfig.GPU_CORES_MAX),
                    config.gpuSha256Mode.ordinal,
                )
                val workMs = System.currentTimeMillis() - t0
                if (n == GPU_UNAVAILABLE) {
                    if (!gpuUnavailable.getAndSet(true)) {
                        AppLog.d(LOG_TAG) { "GPU unavailable (gpuScanNonces returned GPU_UNAVAILABLE)" }
                        onGpuUnavailable?.invoke()
                        startGpuRetryThreadIfNeeded(config)
                    }
                    break
                }
                val scanned = if (n >= 0) (n.toLong() - start + 1) else (nonceEndL - start + 1)
                gpuNoncesScanned.addAndGet(scanned)
                val gpuUtil = (throttle?.effectiveGpuUtilizationPercent ?: config.gpuUtilizationPercent).coerceIn(MiningConfig.GPU_UTILIZATION_MIN, MiningConfig.GPU_UTILIZATION_MAX)
                val throttleSleep = throttle?.throttleSleepMs ?: 0L
                val gpuIntensityDelay = fixedIntensitySleepMs(gpuUtil)
                lastGpuIntensityDelayMs.set(gpuIntensityDelay)
                val totalSleep = gpuIntensityDelay + throttleSleep
                if (totalSleep > 0L) {
                    try {
                        Thread.sleep(totalSleep)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                if (n >= 0) {
                    foundSharesQueue.offer(FoundResult(job.jobId, n, ctx.extranonce2Hex, ctx.ntimeHex, ctx.header76))
                    break
                }
            }
        }.apply { isDaemon = true }
        gpuWorker.start()
        while (gpuWorker.isAlive) {
            if (!ctx.isOfflineRound && !client.isConnected()) {
                AppLog.d(LOG_TAG) { "Connection lost during GPU mining, breaking out to try reconnect" }
                activeJobId.set(null)
                break
            }
            if (client.isConnected() && client.consumeCleanJobsInvalidation()) {
                AppLog.d(LOG_TAG) { "Job changed (clean_jobs), switching to new template" }
                activeJobId.set(null)
            }
            try {
                gpuWorker.join(statusUpdateIntervalMs.toLong())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            val now = System.currentTimeMillis()
            val elapsed = now - roundStartTimeMs
            val gpuProgress = gpuNoncesScanned.get() - roundStartGpuNonces
            if (elapsed >= MiningConstants.WORKER_STUCK_TIMEOUT_MS && gpuProgress == 0L) {
                AppLog.d(LOG_TAG) { "GPU worker stuck (no progress for ${elapsed / 1000}s), requesting interrupt" }
                NativeMiner.gpuRequestInterrupt()
                activeJobId.set(null)
            }
            if (elapsed >= MiningConstants.ROUND_STUCK_TIMEOUT_MS && gpuWorker.isAlive) {
                AppLog.d(LOG_TAG) { "GPU round stuck (${elapsed / 1000}s), interrupting" }
                NativeMiner.gpuRequestInterrupt()
                gpuWorker.interrupt()
                activeJobId.set(null)
            }
        }
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
        val gpuCoresClamped = config.gpuCores.coerceIn(MiningConfig.GPU_CORES_MIN, MiningConfig.GPU_CORES_MAX)
        if (gpuEnabled && !NativeMiner.gpuPipelineReady(gpuCoresClamped, config.gpuSha256Mode.ordinal)) {
            if (!gpuUnavailable.getAndSet(true)) {
                AppLog.d(LOG_TAG) { "GPU init failed at startup" }
                onGpuUnavailable?.invoke()
                startGpuRetryThreadIfNeeded(config)
            }
            gpuEnabled = false
        }
        AppLog.d(LOG_TAG) { "Using $threadCount CPU worker(s), GPU=$gpuEnabled" }

        var lastReconnectAttemptMs = 0L

        fun buildRoundContext(job: StratumJob, difficulty: Double, en1Hex: String, en2Size: Int, isOfflineRound: Boolean): RoundContext {
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
            return RoundContext(job, header76, target, job.ntimeHex, extranonce2Hex, isOfflineRound)
        }

        fun cpuSupervisorLoop() {
            while (running.get()) {
                var j = client.getCurrentJob()
                while (j == null && running.get()) {
                    Thread.sleep(200)
                    j = client.getCurrentJob()
                }
                if (j == null || !running.get()) continue
                val diff = client.getCurrentDifficulty()
                if (diff <= 0.0) continue
                val en1 = client.getExtranonce1Hex() ?: continue
                val en2 = client.getExtranonce2Size().coerceAtLeast(4)
                val ctx = buildRoundContext(j, diff, en1, en2, !client.isConnected())
                runCpuRound(client, config, ctx, threadCount, statusUpdateIntervalMs, statsStartTime)
            }
        }

        fun gpuSupervisorLoop() {
            while (running.get() && gpuEnabled) {
                if (gpuUnavailable.get()) {
                    Thread.sleep(1000)
                    continue
                }
                var j = client.getCurrentJob()
                while (j == null && running.get()) {
                    Thread.sleep(200)
                    j = client.getCurrentJob()
                }
                if (j == null || !running.get()) continue
                val diff = client.getCurrentDifficulty()
                if (diff <= 0.0) continue
                val en1 = client.getExtranonce1Hex() ?: continue
                val en2 = client.getExtranonce2Size().coerceAtLeast(4)
                val ctx = buildRoundContext(j, diff, en1, en2, !client.isConnected())
                runGpuRound(client, config, ctx, statusUpdateIntervalMs, statsStartTime)
            }
        }

        cpuSupervisorThread = Thread({ cpuSupervisorLoop() }, "cpu-supervisor").apply { isDaemon = true; start() }
        if (gpuEnabled) {
            gpuSupervisorThread = Thread({ gpuSupervisorLoop() }, "gpu-supervisor").apply { isDaemon = true; start() }
        }

        while (running.get()) {
            val throttle = throttleStateRef?.get()
            if (throttle?.stopDueToOverheat == true) {
                AppLog.d(LOG_TAG) { "Stopping due to battery overheat" }
                statusRef.set(MiningStatus(MiningStatus.State.Idle, gpuHashrateHs = 0.0,
                    acceptedShares = acceptedShares.get(), rejectedShares = rejectedShares.get(),
                    identifiedShares = identifiedShares.get(), queuedShares = queuedSharesCount(client),
                    bestDifficulty = bestDifficultyRef.get(),
                    blockTemplates = blockTemplatesCount.get(), noncesScanned = totalNoncesScanned.get() + gpuNoncesScanned.get()))
                return
            }

            val now = System.currentTimeMillis()

            if (client.isConnected()) {
                // Connected: ensure job is available for supervisors
            } else {
                val bothUnavailable = isBothWifiAndDataUnavailable?.invoke() == true
                val delayMs = MiningConstants.STRATUM_RECONNECT_RETRY_DELAY_SEC * 1000L
                val delayPassed = now - lastReconnectAttemptMs >= delayMs
                if (!bothUnavailable && delayPassed) {
                    lastReconnectAttemptMs = now
                    AppLog.d(LOG_TAG) { "Disconnected: calling tryReconnect()" }
                    if (client.tryReconnect()) {
                        client.drainDeferredSubmitsAfterConnect()
                        flushPendingShares(client)
                        activeJobId.set(null)
                    }
                }
            }

            while (true) {
                val found = foundSharesQueue.poll() ?: break
                val header80 = StratumHeaderBuilder.header76WithNonce(found.header76, found.nonce)
                val diff = StratumHeaderBuilder.difficultyFromHeader80(header80)
                bestDifficultyRef.updateAndGet { maxOf(it, diff) }
                sessionBestShareDifficultyRef.updateAndGet { maxOf(it, diff) }
                identifiedShares.incrementAndGet()
                val nonceHex = String.format("%08x", (found.nonce.toLong() and 0xFFFFFFFFL))
                if (client.isConnected()) {
                    if (client.getCurrentJob()?.jobId != found.jobId) AppLog.d(LOG_TAG) { "Stale job, submitting anyway so pool sees we are alive" }
                    AppLog.d(LOG_TAG) { String.format(Locale.US, "Share submitted (jobId=%s, nonce 0x%s)", found.jobId, nonceHex) }
                    client.sendSubmit(found.jobId, found.extranonce2Hex, found.ntimeHex, nonceHex) { accepted, errorMessage ->
                        if (accepted) {
                            acceptedShares.incrementAndGet()
                            AppLog.d(LOG_TAG) { String.format(Locale.US, "Share accepted #%d", acceptedShares.get()) }
                        } else {
                            rejectedShares.incrementAndGet()
                            AppLog.e(LOG_TAG) { String.format(Locale.US, "Share rejected #%d: %s", rejectedShares.get(), errorMessage ?: "unknown") }
                        }
                    }
                } else {
                    pendingSharesRepository?.add(PendingSharesRepository.QueuedShare(found.jobId, found.extranonce2Hex, found.ntimeHex, nonceHex))
                    AppLog.d(LOG_TAG) { "Queued share (disconnected)" }
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
                noncesScanned = cpuN + gpuN,
                acceptedShares = acceptedShares.get(),
                rejectedShares = rejectedShares.get(),
                identifiedShares = identifiedShares.get(),
                queuedShares = queuedSharesCount(client),
                bestDifficulty = bestDifficultyRef.get(),
                blockTemplates = blockTemplatesCount.get(),
                connectionLost = !client.isConnected(),
                stratumDifficulty = client.getCurrentDifficulty(),
            ))
            if (now - lastLogTime >= AppLog.STATS_LOG_INTERVAL_MS) {
                AppLog.d(LOG_TAG) {
                    "Stats: ${statsLogExtra?.invoke() ?: ""}CPU ${NumberFormatUtils.formatHashrateWithSpaces(hashrateHs)} GPU ${NumberFormatUtils.formatHashrateWithSpaces(gpuHashrateHs)} H/s, nonces=${NumberFormatUtils.formatWithSpaces(totalNoncesScanned.get() + gpuNoncesScanned.get())}, blockTemplate=${NumberFormatUtils.formatIntWithSpaces(blockTemplatesCount.get().toInt())}, CPU_Int Delay=${NumberFormatUtils.formatDurationMmSs(lastCpuIntensityDelayMs.get())}, GPU_Int Delay=${NumberFormatUtils.formatDurationMmSs(lastGpuIntensityDelayMs.get())}"
                }
                lastLogTime = now
            }

            Thread.sleep(statusUpdateIntervalMs.toLong().coerceAtMost(200L))
        }
    }

    /**
     * Starts a dedicated background thread that periodically retries GPU init while GPU is unavailable.
     * The thread sleeps [MiningConstants.GPU_RETRY_INTERVAL_MS] between attempts and exits when mining stops, GPU becomes
     * available again, or a retry succeeds.
     */
    private fun startGpuRetryThreadIfNeeded(config: MiningConfig) {
        if (!gpuRetryThreadRunning.compareAndSet(false, true)) return
        val gpuCores = config.gpuCores.coerceIn(MiningConfig.GPU_CORES_MIN, MiningConfig.GPU_CORES_MAX)
        val thread = Thread({
            try {
                var retryCount = 0
                while (running.get() && gpuUnavailable.get()) {
                    try {
                        Thread.sleep(MiningConstants.GPU_RETRY_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (!running.get() || !gpuUnavailable.get()) break
                    retryCount++
                    AppLog.d(LOG_TAG) { "GPU retry attempt #$retryCount starting" }
                    val available = NativeMiner.gpuIsAvailable() &&
                        NativeMiner.gpuPipelineReady(gpuCores, config.gpuSha256Mode.ordinal)
                    if (available) {
                        gpuUnavailable.set(false)
                        AppLog.d(LOG_TAG) { "GPU init succeeded; resuming GPU mining" }
                        break
                    } else {
                        AppLog.d(LOG_TAG) {
                            "GPU init failed, retry attempt #$retryCount, will retry in ${MiningConstants.GPU_RETRY_INTERVAL_MS / 1000}s"
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
