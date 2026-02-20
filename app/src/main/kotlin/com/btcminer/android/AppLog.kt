package com.btcminer.android

import android.util.Log

/**
 * Global switch for app logging. In release builds logging is disabled (BuildConfig.DEBUG is false).
 * When using a debug build, set ENABLE_DEBUG_LOGS to false to mute debug logs.
 * STATS_LOG_INTERVAL_MS: how often hashrate/stats (nonces, accepted, rejected) are logged, in milliseconds.
 */
object AppLog {
    /** Set to false to mute debug logs when using debug build on device; flip back to true when debugging. */
    @PublishedApi
    internal const val ENABLE_DEBUG_LOGS = false //true

    /** Interval in ms between mining stats log lines (hashrate, nonces, accepted, rejected). */
    const val STATS_LOG_INTERVAL_MS = 1000L

    @JvmStatic
    inline fun d(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG && ENABLE_DEBUG_LOGS) Log.d(tag, msg())
    }

    @JvmStatic
    inline fun e(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG) Log.e(tag, msg())
    }
}
