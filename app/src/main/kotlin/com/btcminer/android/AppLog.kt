package com.btcminer.android

import android.util.Log

/**
 * Global switch for app logging. In release builds logging is disabled (BuildConfig.DEBUG is false).
 * STATS_LOG_INTERVAL_MS: how often hashrate/stats (nonces, accepted, rejected) are logged, in milliseconds.
 */
object AppLog {
    /** Interval in ms between mining stats log lines (hashrate, nonces, accepted, rejected). */
    const val STATS_LOG_INTERVAL_MS = 1000L

    @JvmStatic
    inline fun d(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg())
    }

    @JvmStatic
    inline fun e(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG) Log.e(tag, msg())
    }
}
