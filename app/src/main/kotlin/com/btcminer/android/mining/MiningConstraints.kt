package com.btcminer.android.mining

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.btcminer.android.config.MiningConfig

/**
 * Checks config-based constraints before/during mining:
 * network (WiFi only vs any), charging (mine only when charging).
 */
object MiningConstraints {

    fun isNetworkOk(context: Context, config: MiningConfig): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return when {
            config.wifiOnly -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            else -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }

    /**
     * True when the device has neither WiFi nor cellular data (e.g. no active network, or active network has neither transport).
     * Used to gate "Reconnecting..." UI and Stratum retry logic.
     */
    fun isBothWifiAndDataUnavailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(network) ?: return true
        return !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    fun isChargingOk(context: Context, config: MiningConfig): Boolean {
        if (!config.mineOnlyWhenCharging) return true
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return plugged != 0
    }

    fun canStartMining(context: Context, config: MiningConfig): Boolean =
        config.isValidForMining() && isNetworkOk(context, config) && isChargingOk(context, config)
}
