package com.btcminer.android.mining

import com.btcminer.android.config.MiningConfig

/**
 * Abstraction for the mining core (Stratum client + hashing).
 * Implementations: [NativeMiningEngine].
 */
interface MiningEngine {

    /** Start mining with the given config. No-op if already running. */
    fun start(config: MiningConfig)

    /** Stop mining. No-op if not running. */
    fun stop()

    /** Whether the engine is currently running (connecting or mining). */
    fun isRunning(): Boolean

    /** Current status for UI (e.g. "Connecting", "Mining", "Stopped"). */
    fun getStatus(): MiningStatus

    /** Reset persistent UI counters (accepted/rejected/identified shares, block templates, best difficulty, nonces). */
    fun resetAllCounters() {}

    /** Max share difficulty this mining session; cleared at session start / reset. */
    fun getSessionBestShareDifficulty(): Double = 0.0

    /** Clears per-session display counters in the engine (session best share difficulty). */
    fun resetSessionScopeDisplay() {}

    /** Stratum `mining.notify` param 6 (`nbitsHex`) for current job, or null if none / engine has no Stratum client. */
    fun getCurrentStratumNbitsHex(): String? = null

    /** Last raw JSON line received from the pool (Stratum), or null. Cleared when engine disconnects. */
    fun getLastStratumJsonIn(): String? = null

    /** Last raw JSON line sent to the pool (Stratum), or null. Cleared when engine disconnects. */
    fun getLastStratumJsonOut(): String? = null

    /** If the last outbound line was a share [sendSubmit], which scanner produced it; else null. */
    fun getLastStratumJsonOutSubmitSource(): StratumOutboundSubmitSource? = null

    /** Session/lifetime counters split by identify source (CPU, GPU). */
    fun getIdentifiedSharesBySource(): Pair<Long, Long> = 0L to 0L
}

data class MiningStatus(
    val state: State,
    val hashrateHs: Double = 0.0,
    val gpuHashrateHs: Double = 0.0,
    val gpuAvailable: Boolean = true,
    val noncesScanned: Long = 0L,
    val acceptedShares: Long = 0L,
    val rejectedShares: Long = 0L,
    val identifiedShares: Long = 0L,
    /** Shares queued for re-submit (disk repo) plus Stratum deferred / in-flight submits; not persisted in stats prefs. */
    val queuedShares: Long = 0L,
    val bestDifficulty: Double = 0.0,
    val blockTemplates: Long = 0L,
    val lastError: String? = null,
    /** True when mining is active but Stratum connection is lost (reconnecting). */
    val connectionLost: Boolean = false,
    /** Pool share difficulty from last `mining.set_difficulty` (null when not mining / unknown). */
    val stratumDifficulty: Double? = null,
) {
    enum class State { Idle, Connecting, Mining, Error }
}
