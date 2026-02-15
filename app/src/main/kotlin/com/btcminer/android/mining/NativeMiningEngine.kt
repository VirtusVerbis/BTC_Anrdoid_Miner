package com.btcminer.android.mining

import com.btcminer.android.AppLog
import com.btcminer.android.config.MiningConfig
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Mining engine using native hashing (Phase 2) and Kotlin Stratum client (Phase 3).
 * Implements [MiningEngine]; used when Option A1 is active.
 */
class NativeMiningEngine(
    private val throttleStateRef: AtomicReference<ThrottleState>? = null,
) : MiningEngine {

    private companion object {
        private const val LOG_TAG = "Mining"
        private const val CHUNK_SIZE = 2L * 1024 * 1024
        private const val MAX_NONCE = 0xFFFFFFFFL
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
    private val reconnectTargetRef = AtomicReference<Pair<String, Int>?>(null)

    override fun start(config: MiningConfig) {
        if (running.getAndSet(true)) return
        AppLog.d(LOG_TAG) { "start()" }
        // Persistent counters (acceptedShares, rejectedShares, identifiedShares, totalNoncesScanned, bestDifficultyRef, blockTemplatesCount) are not reset here

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

        // First connect on this thread so the service sees correct isRunning() when start() returns
        val client = StratumClient(host, port, username, password, useTls = useTls,
            onReconnectRequest = { h, p -> reconnectTargetRef.set(Pair(h, p)) },
            onTemplateReceived = { blockTemplatesCount.incrementAndGet() })
        val err = client.connect()
        if (err != null) {
            AppLog.e(LOG_TAG) { "Connect failed: $err" }
            statusRef.set(MiningStatus(MiningStatus.State.Error, lastError = err))
            running.set(false)
            return
        }
        AppLog.d(LOG_TAG) { "Connected, starting mining" }
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

        var backoff = 5000L
        val maxBackoff = 300_000L
        val minerThread = Thread {
            try {
                runMiningLoop(client, config)
            } catch (_: InterruptedException) { }
            finally {
                client.disconnect()
                clientRef.set(null)
            }
            // Reconnect loop (only after first connection drops)
            var connectHost = host
            var connectPort = port
            var connectTls = useTls
            while (running.get()) {
                val target = reconnectTargetRef.getAndSet(null)
                if (target != null) {
                    connectHost = target.first
                    connectPort = target.second
                    connectTls = connectPort == 443 || useTls
                }
                val client2 = StratumClient(connectHost, connectPort, username, password, useTls = connectTls,
                    onReconnectRequest = { h, p -> reconnectTargetRef.set(Pair(h, p)) },
                    onTemplateReceived = { blockTemplatesCount.incrementAndGet() })
                val err2 = client2.connect()
                if (err2 != null) {
                    AppLog.e(LOG_TAG) { "Connect failed: $err2" }
                    statusRef.set(MiningStatus(MiningStatus.State.Error, lastError = err2))
                    AppLog.d(LOG_TAG) { "Reconnecting in ${backoff / 1000}s" }
                    Thread.sleep(backoff)
                    backoff = minOf(backoff * 2, maxBackoff)
                    continue
                }
                AppLog.d(LOG_TAG) { "Reconnected, starting mining" }
                clientRef.set(client2)
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
                try {
                    runMiningLoop(client2, config)
                } catch (_: InterruptedException) { }
                finally {
                    client2.disconnect()
                    clientRef.set(null)
                }
                backoff = 5000L
            }
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

    override fun isRunning(): Boolean = running.get() && clientRef.get()?.isRunning() == true

    override fun getStatus(): MiningStatus = statusRef.get()

    private fun runMiningLoop(client: StratumClient, config: MiningConfig) {
        val en1Hex = client.getExtranonce1Hex() ?: return
        val en2Size = client.getExtranonce2Size().coerceAtLeast(4)
        val statsStartTime = System.currentTimeMillis()
        gpuNoncesScanned.set(0)
        var lastLogTime = statsStartTime
        val statusUpdateIntervalMs = config.statusUpdateIntervalMs.coerceIn(MiningConfig.STATUS_UPDATE_INTERVAL_MIN, MiningConfig.STATUS_UPDATE_INTERVAL_MAX)
        val threadCount = config.maxWorkerThreads.coerceIn(1, Runtime.getRuntime().availableProcessors())
        val gpuEnabled = config.gpuCores > 0 && NativeMiner.gpuIsAvailable()
        AppLog.d(LOG_TAG) { "Using $threadCount CPU worker(s), GPU=${gpuEnabled}" }

        while (running.get() && client.isRunning()) {
            val throttle = throttleStateRef?.get()
            if (throttle?.stopDueToOverheat == true) {
                AppLog.d(LOG_TAG) { "Stopping due to battery overheat" }
                statusRef.set(MiningStatus(MiningStatus.State.Idle, gpuHashrateHs = 0.0,
                    acceptedShares = acceptedShares.get(), rejectedShares = rejectedShares.get(),
                    identifiedShares = identifiedShares.get(), bestDifficulty = bestDifficultyRef.get(),
                    blockTemplates = blockTemplatesCount.get(), noncesScanned = totalNoncesScanned.get()))
                return
            }
            var job = client.getCurrentJob()
            while (job == null && running.get() && client.isRunning()) {
                Thread.sleep(200)
                job = client.getCurrentJob()
            }
            job ?: return
            val difficulty = client.getCurrentDifficulty()
            if (difficulty <= 0.0) {
                Thread.sleep(200)
                continue
            }

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
                    val workerJobId = job.jobId
                    while (running.get() && foundRef.get() == null && activeJobId.get() == workerJobId) {
                        if (throttleStateRef?.get()?.stopDueToOverheat == true) break
                        if (client.getCurrentJob()?.jobId != job.jobId || client.consumeCleanJobsInvalidation()) break
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
                        val workerJobId = job.jobId
                        while (running.get() && foundRef.get() == null && activeJobId.get() == workerJobId) {
                            if (throttleStateRef?.get()?.stopDueToOverheat == true) break
                            val throttle = throttleStateRef?.get()
                            val sleepMs = throttle?.throttleSleepMs ?: 0L
                            if (sleepMs > 0L) {
                                Thread.sleep(sleepMs)
                            } else {
                                val gpuUtil = config.gpuUtilizationPercent.coerceIn(MiningConfig.GPU_UTILIZATION_MIN, MiningConfig.GPU_UTILIZATION_MAX)
                                if (gpuUtil < 100) {
                                    Thread.sleep((100 - gpuUtil) * 20L / 100)
                                }
                            }
                            val start = nextChunkStart.getAndAdd(CHUNK_SIZE)
                            if (start > MAX_NONCE) break
                            val nonceEndL = minOf(start + CHUNK_SIZE - 1, MAX_NONCE)
                            val nonceEnd = nonceEndL.toInt()
                            val n = NativeMiner.gpuScanNonces(header76, start.toInt(), nonceEnd, target)
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
                    if (client.getCurrentJob()?.jobId != activeJobId.get() || client.consumeCleanJobsInvalidation()) {
                        AppLog.d(LOG_TAG) { "Job changed, switching to new template" }
                        activeJobId.set(null)
                    }
                    singleWorkers.forEach { it.join(statusUpdateIntervalMs.toLong()) }
                    val now = System.currentTimeMillis()
                    if (now - lastStatusUpdateTime >= statusUpdateIntervalMs) {
                        val elapsedSec = (now - statsStartTime) / 1000.0
                        val hashrateHs = if (elapsedSec > 0) totalNoncesScanned.get() / elapsedSec else 0.0
                        val gpuHashrateHs = if (elapsedSec > 0) gpuNoncesScanned.get() / elapsedSec else 0.0
                        statusRef.set(MiningStatus(
                            state = MiningStatus.State.Mining,
                            hashrateHs = hashrateHs,
                            gpuHashrateHs = gpuHashrateHs,
                            noncesScanned = totalNoncesScanned.get(),
                            acceptedShares = acceptedShares.get(),
                            rejectedShares = rejectedShares.get(),
                            identifiedShares = identifiedShares.get(),
                            bestDifficulty = bestDifficultyRef.get(),
                            blockTemplates = blockTemplatesCount.get(),
                        ))
                        lastStatusUpdateTime = now
                    }
                    if (now - lastLogTime >= AppLog.STATS_LOG_INTERVAL_MS) {
                        val elapsedSec = (now - statsStartTime) / 1000.0
                        val hashrateHs = if (elapsedSec > 0) totalNoncesScanned.get() / elapsedSec else 0.0
                        val gpuH = if (elapsedSec > 0) gpuNoncesScanned.get() / elapsedSec else 0.0
                        AppLog.d(LOG_TAG) { String.format(Locale.US, "Stats: CPU %.2f GPU %.2f H/s, nonces=%d, accepted=%d, rejected=%d, identified=%d", hashrateHs, gpuH, totalNoncesScanned.get(), acceptedShares.get(), rejectedShares.get(), identifiedShares.get()) }
                        lastLogTime = now
                    }
                }
                val found = foundRef.get()
                if (found != null) foundNonce = found.nonce
            } else {
                val cpuWorkers = (0 until threadCount).map {
                    val workerJobId = job.jobId
                    Thread {
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
                        val workerJobId = job.jobId
                        while (running.get() && foundRef.get() == null && activeJobId.get() == workerJobId) {
                            if (throttleStateRef?.get()?.stopDueToOverheat == true) break
                            val throttle = throttleStateRef?.get()
                            val sleepMs = throttle?.throttleSleepMs ?: 0L
                            if (sleepMs > 0L) {
                                Thread.sleep(sleepMs)
                            } else {
                                val gpuUtil = config.gpuUtilizationPercent.coerceIn(MiningConfig.GPU_UTILIZATION_MIN, MiningConfig.GPU_UTILIZATION_MAX)
                                if (gpuUtil < 100) {
                                    Thread.sleep((100 - gpuUtil) * 20L / 100)
                                }
                            }
                            val start = nextChunkStart.getAndAdd(CHUNK_SIZE)
                            if (start > MAX_NONCE) break
                            val nonceEndL = minOf(start + CHUNK_SIZE - 1, MAX_NONCE)
                            val nonceEnd = nonceEndL.toInt()
                            val n = NativeMiner.gpuScanNonces(header76, start.toInt(), nonceEnd, target)
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
                    if (client.getCurrentJob()?.jobId != activeJobId.get() || client.consumeCleanJobsInvalidation()) {
                        AppLog.d(LOG_TAG) { "Job changed, switching to new template" }
                        activeJobId.set(null)
                    }
                    allWorkers.forEach { it.join(statusUpdateIntervalMs.toLong()) }
                    val now = System.currentTimeMillis()
                    val elapsedSec = (now - statsStartTime) / 1000.0
                    val hashrateHs = if (elapsedSec > 0) totalNoncesScanned.get() / elapsedSec else 0.0
                    val gpuHashrateHs = if (elapsedSec > 0) gpuNoncesScanned.get() / elapsedSec else 0.0
                    statusRef.set(MiningStatus(
                        state = MiningStatus.State.Mining,
                        hashrateHs = hashrateHs,
                        gpuHashrateHs = gpuHashrateHs,
                        noncesScanned = totalNoncesScanned.get(),
                        acceptedShares = acceptedShares.get(),
                        rejectedShares = rejectedShares.get(),
                        identifiedShares = identifiedShares.get(),
                        bestDifficulty = bestDifficultyRef.get(),
                        blockTemplates = blockTemplatesCount.get(),
                    ))
                    if (now - lastLogTime >= AppLog.STATS_LOG_INTERVAL_MS) {
                        AppLog.d(LOG_TAG) { String.format(Locale.US, "Stats: CPU %.2f GPU %.2f H/s, nonces=%d, accepted=%d, rejected=%d, identified=%d", hashrateHs, gpuHashrateHs, totalNoncesScanned.get(), acceptedShares.get(), rejectedShares.get(), identifiedShares.get()) }
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
            if (client.getCurrentJob()?.jobId != job.jobId) {
                AppLog.d(LOG_TAG) { "Stale job, discarding share" }
                continue
            }
            val nonceHex = String.format("%08x", (foundNonce.toLong() and 0xFFFFFFFFL))
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
            val elapsedSec = (System.currentTimeMillis() - statsStartTime) / 1000.0
            val hashrateHs = if (elapsedSec > 0) totalNoncesScanned.get() / elapsedSec else 0.0
            val gpuHashrateHs = if (elapsedSec > 0) gpuNoncesScanned.get() / elapsedSec else 0.0
            statusRef.set(MiningStatus(
                state = MiningStatus.State.Mining,
                hashrateHs = hashrateHs,
                gpuHashrateHs = gpuHashrateHs,
                noncesScanned = totalNoncesScanned.get(),
                acceptedShares = acceptedShares.get(),
                rejectedShares = rejectedShares.get(),
                identifiedShares = identifiedShares.get(),
                bestDifficulty = bestDifficultyRef.get(),
                blockTemplates = blockTemplatesCount.get(),
            ))
        }
    }
}
