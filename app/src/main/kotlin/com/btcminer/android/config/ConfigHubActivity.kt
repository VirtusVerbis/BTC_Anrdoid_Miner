package com.btcminer.android.config

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.btcminer.android.R
import com.btcminer.android.databinding.ActivityConfigHubBinding
import com.btcminer.android.ui.digitalrain.DigitalRainConfigActivity
import com.btcminer.android.ui.digitalrain.DigitalRainPreferences

class ConfigHubActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigHubBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigHubBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val rainPrefs = DigitalRainPreferences(applicationContext)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.config_title)

        binding.buttonHubMining.setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }
        binding.buttonHubDigitalRain.setOnClickListener {
            startActivity(Intent(this, DigitalRainConfigActivity::class.java))
        }
        binding.switchHubDigitalRainMaster.isChecked = rainPrefs.isDigitalRainEnabled()
        binding.switchHubDigitalRainMaster.setOnCheckedChangeListener { _, isChecked ->
            rainPrefs.setDigitalRainEnabled(isChecked)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
