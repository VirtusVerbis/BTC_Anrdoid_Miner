package com.btcminer.android.ui.digitalrain

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.btcminer.android.R
import com.btcminer.android.config.finishAndReturnToMainDashboard
import com.btcminer.android.databinding.ActivityDigitalRainConfigBinding
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

class DigitalRainConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDigitalRainConfigBinding
    private val repo by lazy { DigitalRainSettingsRepository(applicationContext) }
    private val renderPrefs by lazy { DigitalRainPreferences(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDigitalRainConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.digital_rain_title)

        populateUi(repo.load())
        wireSliderLabelUpdates()
        binding.drSwitchDepthEnabled.setOnCheckedChangeListener { _, _ ->
            applyDepthControlsEnabled(binding.drSwitchDepthEnabled.isChecked)
            refreshSliderLabels()
        }

        val saveClick = View.OnClickListener {
            repo.save(collectUi())
            persistRenderBackendFromSwitch()
            Toast.makeText(this, R.string.digital_rain_saved, Toast.LENGTH_SHORT).show()
            finishAndReturnToMainDashboard()
        }
        binding.drButtonSave.setOnClickListener(saveClick)
        binding.drButtonSaveFloating.setOnClickListener(saveClick)

        binding.drButtonRestoreDefaults.setOnClickListener {
            val defaults = repo.resetToDefaults()
            populateUi(defaults)
            Toast.makeText(this, R.string.digital_rain_defaults_restored, Toast.LENGTH_SHORT).show()
        }

        updateFloatingSaveVisibility()
        binding.drScroll.viewTreeObserver.addOnGlobalLayoutListener {
            updateFloatingSaveVisibility()
        }
        binding.drScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            updateFloatingSaveVisibility()
        }
    }

    private fun updateFloatingSaveVisibility() {
        val rect = Rect()
        val inFlowVisible = binding.drButtonSave.getLocalVisibleRect(rect)
        binding.drButtonSaveFloating.visibility = if (inFlowVisible) View.GONE else View.VISIBLE
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun populateUi(s: DigitalRainSettings) {
        populateRenderBackendUi()

        binding.drSliderMatrixFrame.value = s.matrixFrameMs.toFloat().coerceIn(16f, 500f)
        binding.drSliderLineLenMin.value = s.lineLenMin.toFloat()
        binding.drSliderLineLenMax.value = s.lineLenMax.toFloat()
        binding.drSliderSpeedMin.value = s.lineSpeedMin.toFloat()
        binding.drSliderSpeedMax.value = s.lineSpeedMax.toFloat()
        binding.drSliderColumnMult.value = s.columnStartYMultiplier.toFloat().coerceIn(-80f, 0f)
        binding.drSliderLineWidth.value = s.defaultLineWidth.toFloat()
        binding.drSliderLetterHeight.value = s.defaultLetterHeight.toFloat()
        binding.drSliderFontScale.value = s.fontScale.toFloat()
        binding.drSwitchBigText.isChecked = s.useBigText

        binding.drSwitchDepthEnabled.isChecked = s.depthEnabled
        binding.drSliderDepthStreakCount.value = s.depthStreakCount.toFloat().coerceIn(1f, 128f)
        binding.drSliderDepthMaxScale.value = s.depthMaxScalePercent.toFloat().coerceIn(101f, 600f)
        applyDepthControlsEnabled(s.depthEnabled)

        binding.drSliderHeadR.value = s.headCharR.toFloat()
        binding.drSliderHeadG.value = s.headCharG.toFloat()
        binding.drSliderHeadB.value = s.headCharB.toFloat()
        binding.drSliderRainR.value = s.rainTextR.toFloat()
        binding.drSliderRainG.value = s.rainTextG.toFloat()
        binding.drSliderRainB.value = s.rainTextB.toFloat()
        binding.drSliderBgR.value = s.rainBackgroundR.toFloat()
        binding.drSliderBgG.value = s.rainBackgroundG.toFloat()
        binding.drSliderBgB.value = s.rainBackgroundB.toFloat()

        binding.drSwitchAlphabetOnly.isChecked = s.alphabetOnly
        binding.drSliderAscii1Start.value = s.asciiRange1Start.toFloat().coerceIn(32f, 125f)
        binding.drSliderAscii1End.value = s.asciiRange1End.toFloat().coerceIn(33f, 127f)
        binding.drSliderAscii2Start.value = s.asciiRange2Start.toFloat().coerceIn(32f, 125f)
        binding.drSliderAscii2End.value = s.asciiRange2End.toFloat().coerceIn(33f, 127f)

        binding.drSwitchKeyMode.isChecked = s.enableKeyMode
        binding.drSwitchStickyKeyHighlight.isChecked = s.stickyKeyHighlight
        binding.drSliderKeyResetSec.value =
            (s.keyResetTimeMs / 1000L).toFloat().coerceIn(1f, 600f)
        binding.drSliderKeyLengthCols.value = s.keyLengthColumns.toFloat().coerceIn(0f, 128f)
        binding.drSwitchBitcoinOrange.isChecked = s.bitcoinOrangeKeyHighlight

        binding.drSpinnerAnimMode.setSelection(s.animMode.ordinal.coerceIn(0, DigitalRainAnimMode.entries.lastIndex))
        binding.drSliderTextFrame.value = s.textFrameMs.toFloat().coerceIn(50f, 5000f)
        binding.drShowcaseMessageInput.setText(s.showcaseMessage)

        refreshSliderLabels()
    }

    private fun populateRenderBackendUi() {
        val glesOk = DigitalRainPreferences.deviceSupportsGles2(this)
        binding.drSwitchOpenglBackdrop.isEnabled = glesOk
        val backend = renderPrefs.getRenderBackend()
        binding.drSwitchOpenglBackdrop.isChecked =
            backend == DigitalRainRenderBackend.OPENGL_GPU && glesOk
    }

    private fun persistRenderBackendFromSwitch() {
        val glesOk = DigitalRainPreferences.deviceSupportsGles2(this)
        if (!glesOk) {
            renderPrefs.setRenderBackend(DigitalRainRenderBackend.CANVAS_CPU)
            return
        }
        val backend = if (binding.drSwitchOpenglBackdrop.isChecked) {
            DigitalRainRenderBackend.OPENGL_GPU
        } else {
            DigitalRainRenderBackend.CANVAS_CPU
        }
        renderPrefs.setRenderBackend(backend)
    }

    private fun collectUi(): DigitalRainSettings {
        val animOrdinal = binding.drSpinnerAnimMode.selectedItemPosition.coerceIn(0, DigitalRainAnimMode.entries.lastIndex)
        return DigitalRainSettings(
            animMode = DigitalRainAnimMode.entries[animOrdinal],
            defaultLineWidth = binding.drSliderLineWidth.value.roundToInt(),
            defaultLetterHeight = binding.drSliderLetterHeight.value.roundToInt(),
            fontScale = binding.drSliderFontScale.value.roundToInt(),
            useBigText = binding.drSwitchBigText.isChecked,
            columnStartYMultiplier = binding.drSliderColumnMult.value.roundToInt(),
            lineLenMin = binding.drSliderLineLenMin.value.roundToInt(),
            lineLenMax = binding.drSliderLineLenMax.value.roundToInt(),
            lineSpeedMin = binding.drSliderSpeedMin.value.roundToInt(),
            lineSpeedMax = binding.drSliderSpeedMax.value.roundToInt(),
            matrixFrameMs = binding.drSliderMatrixFrame.value.toLong(),
            headCharR = binding.drSliderHeadR.value.roundToInt(),
            headCharG = binding.drSliderHeadG.value.roundToInt(),
            headCharB = binding.drSliderHeadB.value.roundToInt(),
            rainTextR = binding.drSliderRainR.value.roundToInt(),
            rainTextG = binding.drSliderRainG.value.roundToInt(),
            rainTextB = binding.drSliderRainB.value.roundToInt(),
            rainBackgroundR = binding.drSliderBgR.value.roundToInt(),
            rainBackgroundG = binding.drSliderBgG.value.roundToInt(),
            rainBackgroundB = binding.drSliderBgB.value.roundToInt(),
            asciiRange1Start = binding.drSliderAscii1Start.value.roundToInt(),
            asciiRange1End = binding.drSliderAscii1End.value.roundToInt(),
            asciiRange2Start = binding.drSliderAscii2Start.value.roundToInt(),
            asciiRange2End = binding.drSliderAscii2End.value.roundToInt(),
            alphabetOnly = binding.drSwitchAlphabetOnly.isChecked,
            keyResetTimeMs = (binding.drSliderKeyResetSec.value.toLong() * 1000L),
            keyLengthColumns = binding.drSliderKeyLengthCols.value.roundToInt(),
            enableKeyMode = binding.drSwitchKeyMode.isChecked,
            stickyKeyHighlight = binding.drSwitchStickyKeyHighlight.isChecked,
            bitcoinOrangeKeyHighlight = binding.drSwitchBitcoinOrange.isChecked,
            depthEnabled = binding.drSwitchDepthEnabled.isChecked,
            depthStreakCount = binding.drSliderDepthStreakCount.value.roundToInt(),
            depthMaxScalePercent = binding.drSliderDepthMaxScale.value.roundToInt(),
            textFrameMs = binding.drSliderTextFrame.value.toLong(),
            showcaseMessage = binding.drShowcaseMessageInput.text?.toString().orEmpty(),
        )
    }

    private fun wireSliderLabelUpdates() {
        val sliders = listOf(
            binding.drSliderMatrixFrame,
            binding.drSliderLineLenMin,
            binding.drSliderLineLenMax,
            binding.drSliderSpeedMin,
            binding.drSliderSpeedMax,
            binding.drSliderColumnMult,
            binding.drSliderLineWidth,
            binding.drSliderLetterHeight,
            binding.drSliderFontScale,
            binding.drSliderKeyResetSec,
            binding.drSliderKeyLengthCols,
            binding.drSliderDepthStreakCount,
            binding.drSliderDepthMaxScale,
            binding.drSliderTextFrame,
        )
        val noopListener = Slider.OnChangeListener { _, _, _ -> refreshSliderLabels() }
        for (slider in sliders) {
            slider.addOnChangeListener(noopListener)
        }
    }

    private fun refreshSliderLabels() {
        binding.drLabelMatrixFrame.text =
            "${getString(R.string.digital_rain_matrix_frame_ms)}: ${binding.drSliderMatrixFrame.value.toInt()}"
        binding.drLabelLineLenMin.text =
            "${getString(R.string.digital_rain_line_len_min)}: ${binding.drSliderLineLenMin.value.toInt()}"
        binding.drLabelLineLenMax.text =
            "${getString(R.string.digital_rain_line_len_max)}: ${binding.drSliderLineLenMax.value.toInt()}"
        binding.drLabelSpeedMin.text =
            "${getString(R.string.digital_rain_line_speed_min)}: ${binding.drSliderSpeedMin.value.toInt()}"
        binding.drLabelSpeedMax.text =
            "${getString(R.string.digital_rain_line_speed_max)}: ${binding.drSliderSpeedMax.value.toInt()}"
        binding.drLabelColumnMult.text =
            "${getString(R.string.digital_rain_column_y_multiplier)}: ${binding.drSliderColumnMult.value.toInt()}"
        binding.drLabelLineWidth.text =
            "${getString(R.string.digital_rain_line_width)}: ${binding.drSliderLineWidth.value.toInt()}"
        binding.drLabelLetterHeight.text =
            "${getString(R.string.digital_rain_letter_height)}: ${binding.drSliderLetterHeight.value.toInt()}"
        binding.drLabelFontScale.text =
            "${getString(R.string.digital_rain_font_scale)}: ${binding.drSliderFontScale.value.toInt()}"
        binding.drLabelKeyResetSec.text =
            "${getString(R.string.digital_rain_key_reset_sec)}: ${binding.drSliderKeyResetSec.value.toInt()}"
        binding.drLabelKeyLen.text =
            "${getString(R.string.digital_rain_key_length_cols)}: ${binding.drSliderKeyLengthCols.value.toInt()}"
        binding.drLabelTextFrame.text =
            "${getString(R.string.digital_rain_text_frame_ms)}: ${binding.drSliderTextFrame.value.toInt()}"
        binding.drLabelDepthStreakCount.text =
            "${getString(R.string.digital_rain_depth_streak_count)}: ${binding.drSliderDepthStreakCount.value.toInt()}"
        binding.drLabelDepthMaxScale.text =
            "${getString(R.string.digital_rain_depth_max_scale_percent)}: ${binding.drSliderDepthMaxScale.value.toInt()}%"
    }

    private fun applyDepthControlsEnabled(depthOn: Boolean) {
        binding.drSliderDepthStreakCount.isEnabled = depthOn
        binding.drSliderDepthMaxScale.isEnabled = depthOn
        binding.drLabelDepthStreakCount.alpha = if (depthOn) 1f else 0.5f
        binding.drLabelDepthMaxScale.alpha = if (depthOn) 1f else 0.5f
    }
}
