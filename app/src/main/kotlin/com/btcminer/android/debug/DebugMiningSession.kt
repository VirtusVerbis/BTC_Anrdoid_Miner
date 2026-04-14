package com.btcminer.android.debug

import android.util.Log
import com.btcminer.android.BuildConfig
import org.json.JSONObject

/**
 * Debug-mode NDJSON-style lines on logcat tag [TAG] for mining/share diagnostics (session 83e3b7).
 * Filter: adb logcat -s MiningDbg:I
 */
object DebugMiningSession {
    private const val TAG = "MiningDbg"
    private const val SESSION_ID = "83e3b7"

    // #region agent log
    fun log(hypothesisId: String, location: String, message: String, data: Map<String, Any?> = emptyMap()) {
        if (!BuildConfig.DEBUG) return
        val dataJson = JSONObject()
        for ((k, v) in data) {
            when (v) {
                null -> dataJson.put(k, JSONObject.NULL)
                is Number -> dataJson.put(k, v)
                is Boolean -> dataJson.put(k, v)
                else -> dataJson.put(k, v.toString())
            }
        }
        val line = JSONObject().apply {
            put("sessionId", SESSION_ID)
            put("hypothesisId", hypothesisId)
            put("location", location)
            put("message", message)
            put("data", dataJson)
            put("timestamp", System.currentTimeMillis())
        }.toString()
        Log.i(TAG, line)
    }
    // #endregion
}
