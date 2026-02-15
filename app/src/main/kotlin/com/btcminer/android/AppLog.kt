package com.btcminer.android

import android.util.Log

/**
 * Global switch for app logging. Set to false to disable all log output.
 * STATS_LOG_INTERVAL_MS: how often hashrate/stats (nonces, accepted, rejected) are logged, in milliseconds.
 */
object AppLog {
    const val ENABLED = true

    /** Interval in ms between mining stats log lines (hashrate, nonces, accepted, rejected). */
    const val STATS_LOG_INTERVAL_MS = 1000L //15_000L

    @JvmStatic
    inline fun d(tag: String, msg: () -> String) {
        if (ENABLED) Log.d(tag, msg())
    }

    @JvmStatic
    inline fun e(tag: String, msg: () -> String) {
        if (ENABLED) Log.e(tag, msg())
    }
}
