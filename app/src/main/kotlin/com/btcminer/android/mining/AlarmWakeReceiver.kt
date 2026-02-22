package com.btcminer.android.mining

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.Build

/**
 * Receives ELAPSED_REALTIME_WAKEUP alarms and starts MiningForegroundService with ACTION_ALARM_WAKEUP.
 * Optionally holds a short PARTIAL_WAKE_LOCK so the process stays up until the service handles the intent.
 */
class AlarmWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != MiningForegroundService.ACTION_ALARM_WAKEUP) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val shortLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "btcminer:alarm").apply {
            setReferenceCounted(false)
            acquire(10_000L) // 10 s max so service can run
        }
        try {
            val serviceIntent = Intent(context, MiningForegroundService::class.java).apply {
                action = MiningForegroundService.ACTION_ALARM_WAKEUP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } finally {
            if (shortLock.isHeld) shortLock.release()
        }
    }
}
