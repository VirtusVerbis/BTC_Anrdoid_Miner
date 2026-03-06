package com.btcminer.android.mining

/**
 * Global constants for mining (reconnect, queue, etc.).
 */
object MiningConstants {
    /** Stratum reconnect retry delay in seconds. Used for both WiFi and cell. */
    const val STRATUM_RECONNECT_RETRY_DELAY_SEC = 10//60 //300
    /** Interval (ms) between GPU init retry attempts when GPU is unavailable. */
    const val GPU_RETRY_INTERVAL_MS = 60_000L
}
