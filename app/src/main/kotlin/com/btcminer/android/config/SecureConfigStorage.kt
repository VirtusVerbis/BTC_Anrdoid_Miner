package com.btcminer.android.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted key-value storage for mining config using Android Keystore + AES-256.
 * Do not log or expose stored values.
 */
class SecureConfigStorage(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getStr(key: String, default: String = ""): String =
        prefs.getString(key, default) ?: default

    fun putStr(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getInt(key: String, default: Int): Int =
        prefs.getInt(key, default)

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    /** Run multiple edits and commit synchronously. Returns true if commit succeeded. */
    fun commitBatch(block: (SharedPreferences.Editor) -> Unit): Boolean =
        prefs.edit().also(block).commit()

    companion object {
        private const val PREFS_NAME = "btc_miner_encrypted_config"

        const val KEY_STRATUM_URL = "stratum_url"
        const val KEY_STRATUM_PORT = "stratum_port"
        const val KEY_STRATUM_USER = "stratum_user"
        const val KEY_STRATUM_PASS = "stratumpass"
        const val KEY_BITCOIN_ADDRESS = "bitcoin_address"
        const val KEY_LIGHTNING_ADDRESS = "lightning_address"
        const val KEY_WORKER_NAME = "worker_name"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_MINE_ONLY_WHEN_CHARGING = "mine_only_when_charging"
        const val KEY_MAX_INTENSITY_PERCENT = "max_intensity_percent"
        const val KEY_STATUS_UPDATE_INTERVAL_MS = "status_update_interval_ms"
        const val KEY_MAX_WORKER_THREADS = "max_worker_threads"
        const val KEY_BATTERY_TEMP_FAHRENHEIT = "battery_temp_fahrenheit"
        const val KEY_MAX_BATTERY_TEMP_C = "max_battery_temp_c"
        const val KEY_HASHRATE_TARGET_HPS = "hashrate_target_hps"
        const val KEY_GPU_CORES = "gpu_cores"
        const val KEY_GPU_UTILIZATION_PERCENT = "gpu_utilization_percent"
    }
}
