package com.btcminer.android.mining

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists pending shares (jobId, extranonce2Hex, ntimeHex, nonceHex) to disk for flush on reconnect.
 * Cap: 100 shares; when at limit, oldest is dropped before adding.
 */
class PendingSharesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** One share to submit (same fields as StratumClient.sendSubmit). */
    data class QueuedShare(
        val jobId: String,
        val extranonce2Hex: String,
        val ntimeHex: String,
        val nonceHex: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put(KEY_JOB_ID, jobId)
            put(KEY_EXTRANONCE2_HEX, extranonce2Hex)
            put(KEY_NTIME_HEX, ntimeHex)
            put(KEY_NONCE_HEX, nonceHex)
        }

        companion object {
            fun fromJson(obj: JSONObject): QueuedShare = QueuedShare(
                jobId = obj.optString(KEY_JOB_ID, ""),
                extranonce2Hex = obj.optString(KEY_EXTRANONCE2_HEX, ""),
                ntimeHex = obj.optString(KEY_NTIME_HEX, ""),
                nonceHex = obj.optString(KEY_NONCE_HEX, ""),
            )
        }
    }

    private val lock = Any()

    /**
     * Appends a share. If already at [MAX_QUEUE_SIZE], removes oldest then appends. Persists to disk.
     */
    fun add(share: QueuedShare) {
        synchronized(lock) {
            val list = getAllMutable()
            while (list.size >= MAX_QUEUE_SIZE && list.isNotEmpty()) {
                list.removeAt(0)
            }
            list.add(share)
            persist(list)
        }
    }

    /**
     * Loads all pending shares from disk (for flush on reconnect). Returns a new list.
     */
    fun getAll(): List<QueuedShare> = synchronized(lock) { getAllMutable().toList() }

    /**
     * Removes all shares and persists. Call after successful flush.
     */
    fun clear() {
        synchronized(lock) {
            persist(emptyList())
        }
    }

    private fun getAllMutable(): MutableList<QueuedShare> {
        val json = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { QueuedShare.fromJson(arr.getJSONObject(it)) }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun persist(list: List<QueuedShare>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "pending_shares"
        private const val KEY_QUEUE = "queue"
        private const val KEY_JOB_ID = "job_id"
        private const val KEY_EXTRANONCE2_HEX = "extranonce2_hex"
        private const val KEY_NTIME_HEX = "ntime_hex"
        private const val KEY_NONCE_HEX = "nonce_hex"
        const val MAX_QUEUE_SIZE = 100
    }
}
