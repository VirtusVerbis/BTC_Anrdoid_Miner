package com.btcminer.android.mining

/**
 * Global constants for mining (reconnect, queue, etc.).
 */
object MiningConstants {
    /** Stratum reconnect retry delay in seconds. Used for both WiFi and cell. */
    const val STRATUM_RECONNECT_RETRY_DELAY_SEC = 60 //300
}
