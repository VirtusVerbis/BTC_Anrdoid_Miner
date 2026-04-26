package com.btcminer.android

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.btcminer.android.config.ConfigActivity
import com.btcminer.android.config.MiningConfigRepository
import com.btcminer.android.databinding.ActivityMainBinding
import com.btcminer.android.mining.MiningConstraints
import com.btcminer.android.mining.MiningForegroundService
import com.btcminer.android.mining.MiningStatsRepository
import com.btcminer.android.mining.MiningStatus
import com.btcminer.android.mining.NativeMiner
import com.btcminer.android.mining.StratumHeaderBuilder
import com.btcminer.android.mining.StratumOutboundSubmitSource
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LegendEntry
import com.btcminer.android.network.CertPins
import com.btcminer.android.util.BitcoinAddressValidator
import com.btcminer.android.util.NumberFormatUtils
import com.btcminer.android.util.StratumJsonUiFormatter
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.math.BigDecimal
import java.net.URLEncoder
import java.util.Locale
import java.util.Scanner
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        /** Full cycle period (ms) for throttle flash (red/white). Low frequency for safety. */
        private const val THROTTLE_FLASH_PERIOD_MS = 1000L
        /** How often to fetch wallet balance from Mempool.space (ms). */
        private const val MEMPOOL_BALANCE_FETCH_INTERVAL_MS = 3_600_000L  // 1 hour
        private const val MEMPOOL_UTXO_URL = "https://mempool.space/api/address"
        /** Rolling window size for the short hash rate chart (matches prior ~2 min at 1 Hz). */
        private const val HASHRATE_CHART_TWO_MIN_SAMPLES = 120
        private const val STATE_HASH_CHART_MODE = "hashChartMode"
    }

    private enum class HashChartMode {
        TwoMinute,
        Session,
        ;

        fun toggle(): HashChartMode = when (this) {
            TwoMinute -> Session
            Session -> TwoMinute
        }
    }

    private var hashChartMode = HashChartMode.TwoMinute

    private lateinit var binding: ActivityMainBinding
    private lateinit var configRepository: MiningConfigRepository
    private val statsRepository by lazy { MiningStatsRepository(applicationContext) }

    private var page1Fragment: DashboardStatsPage1Fragment? = null
    private var page2Fragment: DashboardStatsPage2Fragment? = null
    private var page3Fragment: DashboardStatsPage3Fragment? = null
    private var page4Fragment: DashboardStatsPage4Fragment? = null
    private var page5Fragment: DashboardStatsPage5Fragment? = null
    private var dashboardTabMediator: TabLayoutMediator? = null
    private var chartHashrateFragment: ChartHashrateFragment? = null
    private var chartSharesDonutFragment: ChartSharesDonutFragment? = null
    private var chartTabMediator: TabLayoutMediator? = null
    /** Last session CPU/GPU identified counts passed to [updateSharesDonutChart]; null forces next refresh. */
    private var lastDonutIdentifiedCounts: Pair<Long, Long>? = null
    /** Keeps donut hole label aligned with [PieChart] layout; removed when donut fragment view is destroyed. */
    private var donutChartLayoutListener: View.OnLayoutChangeListener? = null
    private var donutChartForLayoutListener: PieChart? = null

    private val dashboardFragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            when (f) {
                is DashboardStatsPage1Fragment -> page1Fragment = f
                is DashboardStatsPage2Fragment -> page2Fragment = f
                is DashboardStatsPage3Fragment -> page3Fragment = f
                is DashboardStatsPage4Fragment -> page4Fragment = f
                is DashboardStatsPage5Fragment -> page5Fragment = f
                is ChartHashrateFragment -> {
                    chartHashrateFragment = f
                    setupChart()
                }
                is ChartSharesDonutFragment -> {
                    chartSharesDonutFragment = f
                    lastDonutIdentifiedCounts = null
                    setupSharesDonutChart()
                }
                else -> return
            }
            refreshDashboardFromPoll()
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            when (f) {
                is DashboardStatsPage1Fragment -> if (page1Fragment === f) page1Fragment = null
                is DashboardStatsPage2Fragment -> if (page2Fragment === f) page2Fragment = null
                is DashboardStatsPage3Fragment -> if (page3Fragment === f) page3Fragment = null
                is DashboardStatsPage4Fragment -> if (page4Fragment === f) page4Fragment = null
                is DashboardStatsPage5Fragment -> if (page5Fragment === f) page5Fragment = null
                is ChartHashrateFragment -> if (chartHashrateFragment === f) chartHashrateFragment = null
                is ChartSharesDonutFragment -> if (chartSharesDonutFragment === f) {
                    donutChartLayoutListener?.let { l ->
                        donutChartForLayoutListener?.removeOnLayoutChangeListener(l)
                    }
                    donutChartLayoutListener = null
                    donutChartForLayoutListener = null
                    chartSharesDonutFragment = null
                }
            }
        }
    }

    /** Full-screen dimensions saved before entering PIP; used to scale root to fit in PIP. */
    private var pipFullWidth = 0
    private var pipFullHeight = 0
    /** Listener that updates root scale when PIP window is resized; removed when leaving PIP. */
    private var pipScaleLayoutListener: View.OnLayoutChangeListener? = null

    private val mempoolOkHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
        if (CertPins.hasMempoolSpacePins()) {
            val pinnerBuilder = CertificatePinner.Builder()
            CertPins.MEMPOOL_SPACE_PINS.forEach { pin -> pinnerBuilder.add("mempool.space", pin) }
            builder.certificatePinner(pinnerBuilder.build())
        }
        builder.build()
    }

    private var miningService: MiningForegroundService? = null
    private var lastBitcoinAddress: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private var flashPhase = false
    private val flashRunnable = object : Runnable {
        override fun run() {
            val service = miningService
            val hashrateThrottle = service?.isHashrateThrottleActive() == true
            val batteryThrottle = service?.isBatteryThrottleActive() == true
            val orange = ContextCompat.getColor(this@MainActivity, R.color.bitcoin_orange)
            val throttleSecondary = ContextCompat.getColor(this@MainActivity, R.color.throttle_secondary)
            val p1 = page1Fragment?.pageBinding
            p1?.hashRateValue?.setTextColor(if (hashrateThrottle) if (flashPhase) Color.RED else throttleSecondary else orange)
            p1?.batteryTempValue?.setTextColor(if (batteryThrottle) if (flashPhase) Color.RED else throttleSecondary else orange)
            flashPhase = !flashPhase
            handler.postDelayed(this, THROTTLE_FLASH_PERIOD_MS / 2)
        }
    }
    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshDashboardFromPoll()
            handler.postDelayed(this, 1000L)
        }
    }

    private val mempoolFetchRunnable: Runnable = object : Runnable {
        override fun run() {
            val self = this
            val address = configRepository.getConfig().bitcoinAddress.trim()
            if (address.isEmpty()) {
                binding.walletBalanceValue.text = "—"
                binding.walletBalanceNote.visibility = View.GONE
                handler.postDelayed(self, MEMPOOL_BALANCE_FETCH_INTERVAL_MS)
                return
            }
            if (!BitcoinAddressValidator.isValidAddress(address)) {
                binding.walletBalanceValue.text = "—"
                binding.walletBalanceNote.setText(R.string.wallet_balance_invalid_address)
                binding.walletBalanceNote.visibility = View.VISIBLE
                handler.postDelayed(self, MEMPOOL_BALANCE_FETCH_INTERVAL_MS)
                return
            }
            Thread {
                var result: String? = null
                var certFailure = false
                try {
                    val encoded = URLEncoder.encode(address, "UTF-8")
                    val url = "$MEMPOOL_UTXO_URL/$encoded/utxo"
                    val request = Request.Builder().url(url).get().build()
                    val response = mempoolOkHttpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        result = "INVALID"
                    } else {
                        val text = response.body?.string() ?: ""
                        val arr = JSONArray(text)
                        var sumSat = 0L
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            sumSat += obj.optLong("value", 0L)
                        }
                        val btc = sumSat / 100_000_000.0
                        result = String.format(Locale.US, "₿ %.8f", btc)
                    }
                } catch (e: Exception) {
                    result = "INVALID"
                    certFailure = e is javax.net.ssl.SSLPeerUnverifiedException || e is javax.net.ssl.SSLException
                }
                val toShow = result
                val showCertInvalid = certFailure
                val showCertValidated = result != null && result != "INVALID" && CertPins.hasMempoolSpacePins()
                runOnUiThread {
                    if (CertPins.hasMempoolSpacePins()) {
                        if (showCertValidated) Toast.makeText(this@MainActivity, R.string.mempool_cert_validated, Toast.LENGTH_SHORT).show()
                        if (showCertInvalid) Toast.makeText(this@MainActivity, R.string.mempool_cert_invalid, Toast.LENGTH_SHORT).show()
                    }
                    binding.walletBalanceValue.text = toShow
                    if (toShow == "INVALID") {
                        binding.walletBalanceNote.setText(R.string.wallet_balance_tor_note)
                        binding.walletBalanceNote.visibility = View.VISIBLE
                    } else {
                        binding.walletBalanceNote.visibility = View.GONE
                    }
                    handler.postDelayed(self, MEMPOOL_BALANCE_FETCH_INTERVAL_MS)
                }
            }.start()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            miningService = (binder as? MiningForegroundService.LocalBinder)?.getService()
            lastDonutIdentifiedCounts = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            miningService = null
            // Keep poll running so dashboard shows persisted counters and persisted chart snapshots.
            handler.post {
                updateStatsUi(statsRepository.get(), null)
                updateLifetimeUi()
                updateLifetimePanel2Ui()
                refreshDashboardFromPoll()
            }
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> tryStartMiningService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        savedInstanceState?.getString(STATE_HASH_CHART_MODE)?.let { saved ->
            hashChartMode = runCatching { HashChartMode.valueOf(saved) }.getOrDefault(HashChartMode.TwoMinute)
        }
        configRepository = MiningConfigRepository(applicationContext)

        // Phase 1: verify native miner loads and responds
        Toast.makeText(this, "Native miner: ${NativeMiner.nativeVersion()}", Toast.LENGTH_SHORT).show()
        // Validate: SHA-256 implementation
        val phase2Ok = NativeMiner.nativeTestSha256()
        Toast.makeText(
            this,
            if (phase2Ok) "Validate: SHA-256 OK" else "Validate: SHA-256 FAIL",
            Toast.LENGTH_SHORT
        ).show()

        binding.buttonConfig.setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }
        binding.buttonStartMining.setOnClickListener { onStartMiningClicked() }
        binding.buttonStopMining.setOnClickListener { onStopMiningClicked() }

        supportFragmentManager.registerFragmentLifecycleCallbacks(dashboardFragmentCallbacks, true)
        binding.dashboardPager.adapter = MainPagerAdapter(this)
        val dashboardPageCount = binding.dashboardPager.adapter!!.itemCount
        dashboardTabMediator = TabLayoutMediator(
            binding.dashboardPageIndicator,
            binding.dashboardPager,
        ) { tab, position ->
            tab.text = ""
            tab.contentDescription = getString(
                R.string.dashboard_page_indicator_a11y,
                position + 1,
                dashboardPageCount,
            )
        }.also { it.attach() }
        binding.chartPager.adapter = ChartPagerAdapter(this)
        val chartPageCount = binding.chartPager.adapter!!.itemCount
        chartTabMediator = TabLayoutMediator(
            binding.chartPageIndicator,
            binding.chartPager,
        ) { tab, position ->
            tab.text = ""
            tab.contentDescription = getString(
                R.string.chart_page_indicator_a11y,
                position + 1,
                chartPageCount,
            )
        }.also { it.attach() }
        binding.chartPager.setCurrentItem(0, false)

        // Initialize lastBitcoinAddress to detect changes when returning from Config
        lastBitcoinAddress = configRepository.getConfig().bitcoinAddress.trim()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_HASH_CHART_MODE, hashChartMode.name)
    }

    override fun onDestroy() {
        dashboardTabMediator?.detach()
        dashboardTabMediator = null
        chartTabMediator?.detach()
        chartTabMediator = null
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(dashboardFragmentCallbacks)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Check if Bitcoin address changed in Config and trigger immediate fetch
        val currentAddress = configRepository.getConfig().bitcoinAddress.trim()
        if (currentAddress != lastBitcoinAddress) {
            lastBitcoinAddress = currentAddress
            // Trigger immediate balance check when address changes
            handler.post(mempoolFetchRunnable)
        }
        // One-time overheat banner: if mining was stopped due to overheat, show until user dismisses
        val prefs = getSharedPreferences(MiningForegroundService.OVERHEAT_BANNER_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MiningForegroundService.KEY_SHOW_OVERHEAT_BANNER, false)) {
            Snackbar.make(binding.root, getString(R.string.overheat_banner_message), Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.overheat_banner_dismiss) {
                    prefs.edit().putBoolean(MiningForegroundService.KEY_SHOW_OVERHEAT_BANNER, false).apply()
                }
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(pollRunnable)
        handler.post(flashRunnable)
        handler.post(mempoolFetchRunnable)
        bindService(
            Intent(this, MiningForegroundService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        try {
            unbindService(connection)
        } catch (_: Exception) { }
        miningService = null
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(flashRunnable)
        handler.removeCallbacks(mempoolFetchRunnable)
        val orange = ContextCompat.getColor(this, R.color.bitcoin_orange)
        page1Fragment?.pageBinding?.hashRateValue?.setTextColor(orange)
        page1Fragment?.pageBinding?.batteryTempValue?.setTextColor(orange)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureModeIfSupported()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val root = binding.root
        if (isInPictureInPictureMode) {
            // Keep root at full size and scale it down so entire screen fits in PIP (no reflow/crop).
            if (pipFullWidth > 0 && pipFullHeight > 0) {
                root.layoutParams = (root.layoutParams as? FrameLayout.LayoutParams)?.apply {
                    width = pipFullWidth
                    height = pipFullHeight
                } ?: FrameLayout.LayoutParams(pipFullWidth, pipFullHeight)
                pipScaleLayoutListener?.let { (root.parent as? View)?.removeOnLayoutChangeListener(it) }
                pipScaleLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    updatePipScale()
                }
                (root.parent as? View)?.addOnLayoutChangeListener(pipScaleLayoutListener)
                root.post { updatePipScale() }
            }
        } else {
            pipScaleLayoutListener?.let { (root.parent as? View)?.removeOnLayoutChangeListener(it) }
            pipScaleLayoutListener = null
            // Restore normal layout and scale when leaving PIP.
            root.layoutParams = (root.layoutParams as? FrameLayout.LayoutParams)?.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            } ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            root.scaleX = 1f
            root.scaleY = 1f
            root.requestLayout()
        }
    }

    /** Recompute root scale so full content fits the current PIP window size. */
    private fun updatePipScale() {
        if (pipFullWidth <= 0 || pipFullHeight <= 0) return
        val root = binding.root
        val parent = root.parent as? View ?: return
        val pw = parent.width
        val ph = parent.height
        if (pw > 0 && ph > 0) {
            val scale = minOf(pw.toFloat() / pipFullWidth, ph.toFloat() / pipFullHeight)
            root.pivotX = 0f
            root.pivotY = 0f
            root.scaleX = scale
            root.scaleY = scale
        }
    }

    @Suppress("DEPRECATION")
    private fun enterPictureInPictureModeIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val root = binding.root
        val w = root.width
        val h = root.height
        val dm = resources.displayMetrics
        val width = if (w > 0 && h > 0) w else dm.widthPixels
        val height = if (w > 0 && h > 0) h else dm.heightPixels
        if (w > 0 && h > 0) {
            pipFullWidth = w
            pipFullHeight = h
        }
        // Portrait: height >= width. Ensure portrait Rational so PIP window is always tall.
        val portraitW = minOf(width, height)
        val portraitH = maxOf(width, height)
        val rational = Rational(portraitW, portraitH)
        // Source rect in window coordinates.
        val sourceRect = if (w > 0 && h > 0) {
            val loc = IntArray(2)
            root.getLocationInWindow(loc)
            Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
        } else {
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(rational)
            .setSourceRectHint(sourceRect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(false)
        }
        if (!enterPictureInPictureMode(builder.build())) {
            // PIP not entered (e.g. not allowed by device or policy)
        }
    }

    private fun setupChart() {
        val chartBinding = chartHashrateFragment?.chartBinding ?: return
        val chart = chartBinding.hashRateChart
        val chartTextColor = ContextCompat.getColor(this, R.color.chart_axis_legend)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.legend.setTextColor(chartTextColor)
        chart.xAxis.setDrawLabels(true)
        chart.xAxis.setTextColor(chartTextColor)
        chart.axisLeft.setDrawLabels(true)
        chart.axisLeft.setTextColor(chartTextColor)
        chart.axisRight.isEnabled = true
        chart.axisRight.setDrawLabels(true)
        chart.axisRight.setTextColor(chartTextColor)
        chart.axisRight.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val useF = configRepository.getConfig().batteryTempFahrenheit
                val displayValue = if (useF) (value * 9f / 5f + 32f) else value
                val unit = if (useF) "F" else "C"
                return String.format(Locale.US, "%.1f%s", displayValue, unit)
            }
        }
        chart.setTouchEnabled(true)
        chart.isDragEnabled = false
        chart.setScaleEnabled(false)
        chart.setPinchZoom(false)
        chart.isHighlightPerTapEnabled = false
        chart.isHighlightPerDragEnabled = false
        chart.setExtraOffsets(8f, 8f, 8f, 8f)
        chart.setOnChartGestureListener(object : OnChartGestureListener {
            override fun onChartGestureStart(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {}
            override fun onChartGestureEnd(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {}
            override fun onChartLongPressed(me: MotionEvent?) {}
            override fun onChartDoubleTapped(me: MotionEvent?) {}
            override fun onChartSingleTapped(me: MotionEvent?) {
                hashChartMode = hashChartMode.toggle()
                refreshDashboardFromPoll()
            }

            override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, velocityX: Float, velocityY: Float) {}
            override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) {}
            override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) {}
        })
        chart.contentDescription = getString(R.string.hash_chart_a11y_toggle_hint)
    }

    private fun setupSharesDonutChart() {
        val b = chartSharesDonutFragment?.chartBinding ?: return
        val chart = b.sharesDonutChart
        val chartTextColor = ContextCompat.getColor(this, R.color.chart_axis_legend)
        chart.description.isEnabled = false
        chart.isDrawHoleEnabled = true
        chart.holeRadius = 62f
        chart.transparentCircleRadius = 66f
        chart.setUsePercentValues(false)
        chart.setDrawEntryLabels(false)
        chart.setHoleColor(Color.TRANSPARENT)
        chart.centerText = ""
        chart.legend.isEnabled = true
        chart.legend.textColor = chartTextColor
        chart.legend.verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
        chart.legend.horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
        chart.legend.orientation = Legend.LegendOrientation.HORIZONTAL
        chart.legend.setDrawInside(false)
        chart.legend.form = Legend.LegendForm.SQUARE
        chart.legend.xEntrySpace = 8f
        chart.legend.yEntrySpace = 0f
        chart.setExtraOffsets(20f, 18f, 20f, 18f)
        chart.isHighlightPerTapEnabled = true
        chart.isRotationEnabled = false
        chart.marker = null
        val forwardTouch = View.OnTouchListener { _, ev ->
            SharesDonutRimLabelHelper.forwardTouchToPieChart(chart, b.donutRimLabelsOverlay, ev)
        }
        b.donutRimLabelsOverlay.setOnTouchListener(forwardTouch)
        b.donutRimCpuPct.setOnTouchListener(forwardTouch)
        b.donutRimGpuPct.setOnTouchListener(forwardTouch)
        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                syncDonutRimLabels()
            }

            override fun onNothingSelected() {
                chartSharesDonutFragment?.chartBinding?.let { bb ->
                    SharesDonutRimLabelHelper.hide(bb.donutRimLabelsOverlay, bb.donutRimCpuPct, bb.donutRimGpuPct)
                }
            }
        })
        donutChartLayoutListener?.let { l ->
            donutChartForLayoutListener?.removeOnLayoutChangeListener(l)
        }
        donutChartForLayoutListener = chart
        val centerLabel = b.donutCenterValue
        donutChartLayoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v is PieChart && v.width > 0 && v.height > 0) {
                SharesDonutCenterLabelHelper.updateLayout(v, centerLabel)
            }
        }
        chart.addOnLayoutChangeListener(donutChartLayoutListener)
        chart.post {
            SharesDonutCenterLabelHelper.updateLayout(chart, centerLabel)
        }
    }

    private fun syncDonutRimLabels() {
        val bb = chartSharesDonutFragment?.chartBinding ?: return
        SharesDonutRimLabelHelper.updateIfHighlighted(
            bb.sharesDonutChart,
            bb.donutRimLabelsOverlay,
            bb.donutRimCpuPct,
            bb.donutRimGpuPct,
            getString(R.string.shares_donut_cpu_label),
            getString(R.string.shares_donut_gpu_label),
        )
    }

    /** Pool `mining.set_difficulty` for dashboard (not best share difficulty). */
    private fun formatStratumDifficultyDisplay(status: MiningStatus): String {
        if (status.state != MiningStatus.State.Mining) return "—"
        val d = status.stratumDifficulty ?: return "—"
        if (!d.isFinite() || d <= 0.0) return "—"
        return if (d in 1e-12..1e15) {
            BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
        } else {
            String.format(Locale.US, "%.6g", d)
        }
    }

    private fun updateStatsUi(status: MiningStatus, service: MiningForegroundService?) {
        page1Fragment?.pageBinding?.let { p1 ->
            val hashrateStr = if (status.state == MiningStatus.State.Mining) {
                "${NumberFormatUtils.formatHashrateWithSpaces(status.hashrateHs)} H/s"
            } else {
                "— H/s"
            }
            p1.hashRateValue.text = hashrateStr
            val gpuHashrateStr = when {
                !status.gpuAvailable -> "----"
                status.state == MiningStatus.State.Mining -> "${NumberFormatUtils.formatHashrateWithSpaces(status.gpuHashrateHs)} H/s"
                else -> "—"
            }
            p1.gpuHashRateValue.text = gpuHashrateStr
            val config = configRepository.getConfig()
            val maxCoresUi = Runtime.getRuntime().availableProcessors()
            val cpuCoresForLabel = config.maxWorkerThreads.coerceIn(0, maxCoresUi)
            p1.hashRateLabel.text = getString(R.string.hash_rate_label) + " - " + cpuCoresForLabel
            val gpuCores = config.gpuCores.coerceAtLeast(0)
            val maxWorkGroupSize = NativeMiner.getMaxComputeWorkGroupSize().coerceAtLeast(32)
            val effectiveWorkgroupSize = if (gpuCores > 0) (32 * gpuCores).coerceAtMost(maxWorkGroupSize) else 0
            p1.gpuHashRateLabel.text = getString(R.string.hash_rate_gpu_label) + if (gpuCores > 0) " - $effectiveWorkgroupSize" else ""
            p1.cpuUtilizationValue.text = formatStratumDifficultyDisplay(status)
            p1.noncesValue.text = NumberFormatUtils.formatWithSpaces(status.noncesScanned)
            val (sessionAcc, sessionRej, sessionId) = if (service != null && service.getMiningStartTimeMillis() != null) {
                service.getSessionShareDisplayedCounts()
            } else {
                statsRepository.getLastStoppedSessionShareDisplay()
            }
            p1.acceptedSharesValue.text = sessionAcc.toString()
            p1.rejectedSharesValue.text = sessionRej.toString()
            p1.identifiedSharesValue.text = sessionId.toString()
            p1.queuedSharesValue.text = status.queuedShares.toString()
            val sessionBestDiff = if (service != null && service.getMiningStartTimeMillis() != null) {
                service.getSessionBestDifficultyForDisplay()
            } else {
                statsRepository.getLastStoppedSessionBestBlockDisplay().first
            }
            p1.bestDifficultyValue.text = if (sessionBestDiff > 0.0) String.format(Locale.US, "%.6f", sessionBestDiff) else "—"
            val sessionBlockTemplates = if (service != null && service.getMiningStartTimeMillis() != null) {
                service.getSessionBlockTemplateDisplayedCount()
            } else {
                statsRepository.getLastStoppedSessionBestBlockDisplay().second
            }
            p1.blockTemplateValue.text = sessionBlockTemplates.toString()
            val startMs = service?.getMiningStartTimeMillis()
            val (timerStr, timerLabel) = if (status.state == MiningStatus.State.Mining && startMs != null) {
                formatElapsed(System.currentTimeMillis() - startMs) to getString(R.string.mining_timer_label)
            } else {
                val lastRunMs = statsRepository.getLastRunDurationMs()
                if (lastRunMs > 0) {
                    formatElapsed(lastRunMs) to getString(R.string.mining_timer_last_run)
                } else {
                    "00:00:00:00" to getString(R.string.mining_timer_label)
                }
            }
            p1.miningTimerValue.text = timerStr
            p1.miningTimerLabel.text = timerLabel
            p1.stratumConnectionIcon.alpha = if (status.state == MiningStatus.State.Mining && !status.connectionLost) 1f else 0.3f
        }
        binding.reconnectingBanner.visibility = if (MiningConstraints.isBothWifiAndDataUnavailable(this) && status.connectionLost) View.VISIBLE else View.GONE
    }

    private fun refreshDashboardFromPoll() {
        updateBatteryTempUi()
        val service = miningService
        if (service != null) {
            val status = service.getStatus()
            updateStatsUi(status, service)
            val isMining = status.state == MiningStatus.State.Mining
            if (isMining) {
                val cpu = service.getHashrateHistoryCpu()
                val gpu = service.getHashrateHistoryGpu()
                val elapsed = service.getHashrateHistoryElapsedSec()
                val batt = service.getBatteryTempHistoryCelsius()
                val n = minOf(cpu.size, gpu.size, elapsed.size, batt.size)
                if (n > 0) {
                    updateChart(cpu, gpu, elapsed, batt)
                } else {
                    renderPersistedChartsOrIdleFallback()
                }
                val src = service.getSessionIdentifiedShareSourceCounts()
                if (lastDonutIdentifiedCounts != src) {
                    if (updateSharesDonutChart(src.first, src.second)) {
                        lastDonutIdentifiedCounts = src
                    }
                }
            } else {
                renderPersistedChartsOrIdleFallback()
            }
        } else {
            updateStatsUi(statsRepository.get(), null)
            renderPersistedChartsOrIdleFallback()
        }
        updateLifetimeUi()
        updateLifetimePanel2Ui()
        updateStratumJsonPanels()
        val config = configRepository.getConfig()
        if (config.autoTuningByBatteryTemp && service != null) {
            binding.autoTuneBlock.visibility = View.VISIBLE
            binding.autoTuneValue.text = "${service.getAutoTuningThrottleSleepMs() / 1000}"
            val dir = service.getAutoTuningDirection()
            val color = when (dir) {
                MiningForegroundService.AUTO_TUNING_DIRECTION_DECREASING -> ContextCompat.getColor(this, R.color.auto_tune_decreasing)
                MiningForegroundService.AUTO_TUNING_DIRECTION_INCREASING -> ContextCompat.getColor(this, R.color.bitcoin_orange)
                else -> ContextCompat.getColor(this, R.color.white)
            }
            binding.autoTuneValue.setTextColor(color)
        } else {
            binding.autoTuneBlock.visibility = View.GONE
        }
    }

    private fun renderPersistedChartsOrIdleFallback() {
        val snapshot = statsRepository.getChartSnapshotOrNull()
        if (snapshot != null) {
            updateChart(
                historyCpu = snapshot.cpu.map { it.toDouble() },
                historyGpu = snapshot.gpu.map { it.toDouble() },
                historyElapsedSec = snapshot.elapsedSec,
                batteryTempHistoryCelsius = snapshot.batteryTempC,
            )
            val donut = snapshot.donutCpuShares to snapshot.donutGpuShares
            if (lastDonutIdentifiedCounts != donut) {
                if (updateSharesDonutChart(snapshot.donutCpuShares, snapshot.donutGpuShares)) {
                    lastDonutIdentifiedCounts = donut
                }
            }
        } else {
            val idle = 0L to 0L
            if (lastDonutIdentifiedCounts != idle) {
                if (updateSharesDonutChart(0L, 0L)) {
                    lastDonutIdentifiedCounts = idle
                }
            }
            chartHashrateFragment?.chartBinding?.let { c ->
                c.hashRateChart.data = null
                c.hashChartModeTitle.visibility = View.GONE
                c.hashRateChart.invalidate()
            }
        }
    }

    private fun updateLifetimeUi() {
        val p3 = page3Fragment?.pageBinding ?: return
        val s = statsRepository.getLifetimeStats()
        p3.lifetimeTotalHashValue.text = "${NumberFormatUtils.formatHashrateWithSpaces(s.sumSessionAvgTotalHs)} H/s"
        p3.lifetimeCpuHashValue.text = "${NumberFormatUtils.formatHashrateWithSpaces(s.sumSessionAvgCpuHs)} H/s"
        p3.lifetimeGpuHashValue.text = "${NumberFormatUtils.formatHashrateWithSpaces(s.sumSessionAvgGpuHs)} H/s"
        p3.lifetimeNoncesValue.text = NumberFormatUtils.formatWithSpaces(s.totalNonces)
    }

    private fun updateLifetimePanel2Ui() {
        val p2 = page2Fragment?.pageBinding ?: return
        val idle = statsRepository.get()
        p2.lifetimeAcceptedSharesValue.text = idle.acceptedShares.toString()
        p2.lifetimeRejectedSharesValue.text = idle.rejectedShares.toString()
        p2.lifetimeIdentifiedSharesValue.text = idle.identifiedShares.toString()
        p2.lifetimeBestDifficultyValue.text = if (idle.bestDifficulty > 0.0) String.format(Locale.US, "%.6f", idle.bestDifficulty) else "—"
        p2.lifetimeBlockTemplateValue.text = idle.blockTemplates.toString()
        p2.lifetimeTotalMiningTimeValue.text =
            NumberFormatUtils.formatTotalMiningTimeYyMmDdHhMmSs(statsRepository.getTotalMiningTimeMs())

        val heatMs = statsRepository.getHeatStopSessionMs()
        if (heatMs <= 0L) {
            p2.lifetimeHeatStopValue.text = getString(R.string.heat_stop_default)
        } else {
            val tempC = statsRepository.getHeatStopTempCelsius()
            val useF = configRepository.getConfig().batteryTempFahrenheit
            val tempPart = if (useF) {
                val tempF = tempC * 9f / 5f + 32f
                String.format(Locale.US, "%.1f °F", tempF)
            } else {
                String.format(Locale.US, "%.1f °C", tempC)
            }
            p2.lifetimeHeatStopValue.text =
                "$tempPart - ${NumberFormatUtils.formatElapsedDdHhMmSs(heatMs)}"
        }

        val nbitsHex = miningService?.getCurrentStratumNbitsHex()?.trim().orEmpty()
        val netDiff =
            if (nbitsHex.isNotEmpty()) StratumHeaderBuilder.networkDifficultyFromNbitsHex(nbitsHex) else null
        p2.lifetimeNetworkDifficultyValue.text =
            if (netDiff != null) NumberFormatUtils.formatNetworkDifficultyForUi(netDiff) else "—"
    }

    private fun updateStratumJsonPanels() {
        val p4 = page4Fragment?.pageBinding
        val p5 = page5Fragment?.pageBinding
        if (p4 == null && p5 == null) return

        val service = miningService
        val mining = service != null && service.getStatus().state == MiningStatus.State.Mining

        val idle = getString(R.string.stratum_json_mining_inactive)
        if (!mining) {
            p4?.stratumJsonInputValue?.text = idle
            p5?.stratumJsonOutputValue?.text = idle
            p5?.stratumJsonOutputTitle?.text = getString(R.string.dashboard_page5_stratum_output_title)
            p4?.stratumJsonInputIndicesFooter?.visibility = View.GONE
            p5?.stratumJsonOutputIndicesFooter?.visibility = View.GONE
            return
        }

        val rawIn = service!!.getLastStratumJsonIn().orEmpty()
        val rawOut = service.getLastStratumJsonOut().orEmpty()

        p5?.stratumJsonOutputTitle?.text = when (service.getLastStratumJsonOutSubmitSource()) {
            StratumOutboundSubmitSource.Cpu -> getString(R.string.dashboard_page5_stratum_output_title_cpu_share)
            StratumOutboundSubmitSource.Gpu -> getString(R.string.dashboard_page5_stratum_output_title_gpu_share)
            null -> getString(R.string.dashboard_page5_stratum_output_title)
        }

        p4?.stratumJsonInputValue?.text = if (rawIn.isNotEmpty()) {
            StratumJsonUiFormatter.prettyStratumJsonSpanned(this, rawIn)
        } else {
            "—"
        }
        p5?.stratumJsonOutputValue?.text = if (rawOut.isNotEmpty()) {
            StratumJsonUiFormatter.prettyStratumJsonSpanned(this, rawOut)
        } else {
            "—"
        }

        val inFooter = if (rawIn.isNotEmpty()) {
            StratumJsonUiFormatter.indicesFooter(this, rawIn, isInbound = true)
        } else {
            null
        }
        p4?.stratumJsonInputIndicesFooter?.let { ft ->
            if (inFooter != null) {
                ft.text = inFooter
                ft.visibility = View.VISIBLE
            } else {
                ft.visibility = View.GONE
            }
        }

        val outFooter = if (rawOut.isNotEmpty()) {
            StratumJsonUiFormatter.indicesFooter(this, rawOut, isInbound = false)
        } else {
            null
        }
        p5?.stratumJsonOutputIndicesFooter?.let { ft ->
            if (outFooter != null) {
                ft.text = outFooter
                ft.visibility = View.VISIBLE
            } else {
                ft.visibility = View.GONE
            }
        }
    }

    private fun formatElapsed(elapsedMs: Long): String =
        NumberFormatUtils.formatElapsedDdHhMmSs(elapsedMs)

    private fun updateBatteryTempUi() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: run {
            page1Fragment?.pageBinding?.batteryTempValue?.text = "—"
            return
        }
        val tempTenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val useF = configRepository.getConfig().batteryTempFahrenheit
        val text = if (tempTenthsC == 0) "—" else {
            val tempC = tempTenthsC / 10f
            if (useF) {
                val tempF = tempC * 9f / 5f + 32f
                String.format(Locale.US, "%.1f °F", tempF)
            } else {
                String.format(Locale.US, "%.1f °C", tempC)
            }
        }
        page1Fragment?.pageBinding?.batteryTempValue?.text = text
    }

    private fun clearStatsUi() {
        lastDonutIdentifiedCounts = null
        page1Fragment?.pageBinding?.let { p1 ->
            p1.hashRateValue.text = "0.00 H/s"
            p1.gpuHashRateValue.text = "0.00 H/s"
            p1.cpuUtilizationValue.text = "—"
            p1.miningTimerValue.text = "00:00:00:00"
            p1.batteryTempValue.text = "—"
            p1.bestDifficultyValue.text = "—"
            p1.blockTemplateValue.text = "—"
        }
        binding.walletBalanceValue.text = "—"
        binding.walletBalanceNote.visibility = View.GONE
        chartHashrateFragment?.chartBinding?.let { c ->
            c.hashRateChart.data = null
            c.hashChartModeTitle.visibility = View.GONE
            c.hashRateChart.invalidate()
        }
        chartSharesDonutFragment?.chartBinding?.let { c ->
            val chart = c.sharesDonutChart
            chart.data = null
            chart.centerText = ""
            val empty = getString(R.string.shares_donut_no_shares)
            val orange = ContextCompat.getColor(this, R.color.bitcoin_orange)
            SharesDonutCenterLabelHelper.setContent(c.donutCenterValue, empty, orange, maxLines = 2)
            c.donutCenterValue.contentDescription = empty
            chart.invalidate()
            SharesDonutRimLabelHelper.hide(c.donutRimLabelsOverlay, c.donutRimCpuPct, c.donutRimGpuPct)
            chart.post { SharesDonutCenterLabelHelper.updateLayout(chart, c.donutCenterValue) }
        }
        // Persistent counters (nonces) are not zeroed here; accepted/rejected/identified and best/block on page 1 are live per-session while mining, then last-stopped snapshots from prefs until the next start; lifetime stats on panels 2–3 reset only via Config "Reset All UI Counters"
    }

    private fun formatElapsedChartAxisSeconds(value: Float): String {
        val totalSec = value.toLong().coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    private fun updateChart(
        historyCpu: List<Double>,
        historyGpu: List<Double>,
        historyElapsedSec: List<Float>,
        batteryTempHistoryCelsius: List<Float>,
    ) {
        val chartBinding = chartHashrateFragment?.chartBinding ?: return
        val chart = chartBinding.hashRateChart
        val chartTextColor = ContextCompat.getColor(this, R.color.chart_axis_legend)
        val nAll = minOf(
            historyCpu.size,
            historyGpu.size,
            historyElapsedSec.size,
            batteryTempHistoryCelsius.size,
        )
        if (nAll == 0) {
            chart.data = null
            chartBinding.hashChartModeTitle.visibility = View.GONE
            chart.invalidate()
            return
        }

        val from = when (hashChartMode) {
            HashChartMode.TwoMinute -> maxOf(0, nAll - HASHRATE_CHART_TWO_MIN_SAMPLES)
            HashChartMode.Session -> 0
        }
        val cpuSlice = historyCpu.subList(from, nAll)
        val gpuSlice = historyGpu.subList(from, nAll)
        val battSliceC = batteryTempHistoryCelsius.subList(from, nAll)

        when (hashChartMode) {
            HashChartMode.TwoMinute -> {
                chart.xAxis.valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String = value.toInt().toString()
                }
                chart.xAxis.granularity = 1f
                chart.xAxis.isGranularityEnabled = true
            }
            HashChartMode.Session -> {
                chart.xAxis.valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String = formatElapsedChartAxisSeconds(value)
                }
                chart.xAxis.isGranularityEnabled = false
            }
        }

        val maxSize = cpuSlice.size
        val orange = ContextCompat.getColor(this, R.color.bitcoin_orange)
        val gray = Color.GRAY
        val green = Color.parseColor("#4CAF50")
        val magenta = Color.MAGENTA

        fun xForIndex(i: Int): Float = when (hashChartMode) {
            HashChartMode.TwoMinute -> i.toFloat()
            HashChartMode.Session -> historyElapsedSec[from + i]
        }

        val cpuEntries = cpuSlice.mapIndexed { i, v -> Entry(xForIndex(i), v.toFloat()) }
        val cpuSet = LineDataSet(cpuEntries, "CPU").apply {
            setColor(gray)
            setCircleColor(gray)
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
        }

        val gpuEntries = gpuSlice.mapIndexed { i, v -> Entry(xForIndex(i), v.toFloat()) }
        val gpuSet = LineDataSet(gpuEntries, "GPU").apply {
            setColor(green)
            setCircleColor(green)
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
        }

        val totalHistory = (0 until maxSize).map { i -> cpuSlice[i] + gpuSlice[i] }
        val avg = if (totalHistory.isNotEmpty()) totalHistory.average() else 0.0
        val avgEntries = (0 until maxSize).map { i -> Entry(xForIndex(i), avg.toFloat()) }
        val avgSet = LineDataSet(avgEntries, "Avg").apply {
            setColor(orange)
            setCircleColor(orange)
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            axisDependency = YAxis.AxisDependency.LEFT
        }

        val battEntries = battSliceC.mapIndexedNotNull { i, vC ->
            if (!vC.isFinite()) return@mapIndexedNotNull null
            Entry(xForIndex(i), vC)
        }
        val battSet = LineDataSet(battEntries, "BATT Temp").apply {
            setColor(magenta)
            setCircleColor(magenta)
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            axisDependency = YAxis.AxisDependency.RIGHT
        }

        cpuSet.axisDependency = YAxis.AxisDependency.LEFT
        gpuSet.axisDependency = YAxis.AxisDependency.LEFT

        chart.data = LineData(cpuSet, gpuSet, avgSet, battSet)
        chart.legend.isEnabled = true
        chart.legend.setTextColor(chartTextColor)
        chartBinding.hashChartModeTitle.visibility = View.VISIBLE
        chartBinding.hashChartModeTitle.text = when (hashChartMode) {
            HashChartMode.TwoMinute -> getString(R.string.hash_chart_title_2min)
            HashChartMode.Session -> getString(R.string.hash_chart_title_session)
        }
        chartBinding.hashChartModeTitle.setTextColor(chartTextColor)
        chart.invalidate()
    }

    private fun updateSharesDonutChart(cpuShares: Long, gpuShares: Long): Boolean {
        val b = chartSharesDonutFragment?.chartBinding ?: return false
        val chart = b.sharesDonutChart
        val orange = ContextCompat.getColor(this, R.color.bitcoin_orange)
        val total = (cpuShares + gpuShares).coerceAtLeast(0L)
        if (total <= 0L) {
            chart.data = null
            chart.centerText = ""
            val empty = getString(R.string.shares_donut_no_shares)
            // Same accent as numeric total; switch to chart_axis_legend if contrast in hole is poor on a theme.
            SharesDonutCenterLabelHelper.setContent(b.donutCenterValue, empty, orange, maxLines = 2)
            b.donutCenterValue.contentDescription = empty
            chart.invalidate()
            SharesDonutRimLabelHelper.hide(b.donutRimLabelsOverlay, b.donutRimCpuPct, b.donutRimGpuPct)
            chart.post { SharesDonutCenterLabelHelper.updateLayout(chart, b.donutCenterValue) }
            return true
        }
        val chartTextColor = ContextCompat.getColor(this, R.color.chart_axis_legend)
        val gray = Color.GRAY
        val green = Color.parseColor("#4CAF50")
        val entries = listOf(
            PieEntry(cpuShares.toFloat(), getString(R.string.shares_donut_cpu_label)),
            PieEntry(gpuShares.toFloat(), getString(R.string.shares_donut_gpu_label)),
        )
        val set = PieDataSet(entries, "").apply {
            colors = listOf(gray, green)
            setDrawValues(false)
            valueTextColor = chartTextColor
            setSelectionShift(8f)
        }
        chart.data = PieData(set)
        chart.centerText = ""
        SharesDonutCenterLabelHelper.setContent(b.donutCenterValue, total.toString(), orange, maxLines = 1)
        b.donutCenterValue.contentDescription = getString(R.string.shares_donut_center_value_a11y, total)
        chart.legend.isEnabled = true
        chart.legend.setCustom(
            listOf(
                LegendEntry(
                    "${getString(R.string.shares_donut_cpu_label)}: $cpuShares",
                    Legend.LegendForm.SQUARE,
                    10f,
                    2f,
                    null,
                    gray,
                ),
                LegendEntry(
                    "${getString(R.string.shares_donut_gpu_label)}: $gpuShares",
                    Legend.LegendForm.SQUARE,
                    10f,
                    2f,
                    null,
                    green,
                ),
            ),
        )
        chart.invalidate()
        syncDonutRimLabels()
        chart.post { SharesDonutCenterLabelHelper.updateLayout(chart, b.donutCenterValue) }
        return true
    }

    private fun onStartMiningClicked() {
        val config = configRepository.getConfig()
        if (!config.isValidForMining()) {
            Toast.makeText(this, R.string.mining_start_fail_config, Toast.LENGTH_SHORT).show()
            return
        }
        if (!MiningConstraints.isNetworkOk(this, config)) {
            Toast.makeText(this, R.string.mining_start_fail_network, Toast.LENGTH_SHORT).show()
            return
        }
        if (!MiningConstraints.isChargingOk(this, config)) {
            Toast.makeText(this, R.string.mining_start_fail_charging, Toast.LENGTH_SHORT).show()
            return
        }
        if (!config.hasActiveHashingConfig()) {
            Toast.makeText(this, R.string.mining_start_fail_both_hashers_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        // Refresh Bitcoin balance when starting mining
        handler.post(mempoolFetchRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            tryStartMiningService()
        }
    }

    private fun tryStartMiningService() {
        startService(Intent(this, MiningForegroundService::class.java).apply {
            action = MiningForegroundService.ACTION_START
        })
    }

    private fun onStopMiningClicked() {
        startService(Intent(this, MiningForegroundService::class.java).apply {
            action = MiningForegroundService.ACTION_STOP
        })
    }
}
