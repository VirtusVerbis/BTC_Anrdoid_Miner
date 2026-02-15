package com.btcminer.android.mining

import com.btcminer.android.config.MiningConfig

/**
 * Abstraction for the mining core (Stratum client + hashing).
 * Implementations: [StubMiningEngine] for now; cgminer-based engine later.
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
}

data class MiningStatus(
    val state: State,
    val hashrateHs: Double = 0.0,
    val gpuHashrateHs: Double = 0.0,
    val noncesScanned: Long = 0L,
    val acceptedShares: Long = 0L,
    val rejectedShares: Long = 0L,
    val identifiedShares: Long = 0L,
    val lastError: String? = null,
) {
    enum class State { Idle, Connecting, Mining, Error }
}
