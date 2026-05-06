package com.btcminer.android.config

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.btcminer.android.MainActivity

internal fun AppCompatActivity.finishAndReturnToMainDashboard() {
    startActivity(
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
    )
    finish()
}
