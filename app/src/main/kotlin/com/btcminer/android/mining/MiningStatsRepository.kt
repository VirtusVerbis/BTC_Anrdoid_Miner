package com.btcminer.android.mining

import android.content.Context
import android.content.SharedPreferences
import com.btcminer.android.AppLog
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/**
 * Persists five dashboard counters (accepted/rejected/identified shares, best difficulty, block templates)
 * to a plain SharedPreferences file, separate from the encrypted config vault.
 * Nonces are not persisted (per-round only). Values are only written when
 * current is greater than stored (monotonic), except on reset which writes zeros.
 *
 * Also persists **lifetime** aggregates (sum of per-session average CPU/GPU hash rates and cumulative
 * session nonces), updated when a mining session ends; cleared only via [saveZeros].
 *
 * **Last stopped session** accepted/rejected/identified (dashboard page 1 while idle): written when
 * mining stops, cleared when a new session starts or via [saveZeros].
 *
 * **Last stopped session** best share difficulty and block-template delta (panel #1 while idle): same lifecycle.
 *
 * **Total mining time** (wall clock summed across sessions): [addTotalMiningTimeMs], cleared only via [saveZeros].
 *
 * **Heat stop** (last 43°C hard stop: session ms + °C at stop): [setHeatStopSnapshot] / [clearHeatStopForNewSession];
 * not cleared by [saveZeros].
 */
class MiningStatsRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Reads the five persisted counters and returns them as a [MiningStatus] with [MiningStatus.State.Idle].
     * Nonces are not persisted; noncesScanned is always 0.
     */
    fun get(): MiningStatus {
        val repoQueued = PendingSharesRepository(appContext).getAll().size.toLong()
        return MiningStatus(
            state = MiningStatus.State.Idle,
            noncesScanned = 0L,
            acceptedShares = prefs.getLong(KEY_ACCEPTED_SHARES, 0L),
            rejectedShares = prefs.getLong(KEY_REJECTED_SHARES, 0L),
            identifiedShares = prefs.getLong(KEY_IDENTIFIED_SHARES, 0L),
            queuedShares = repoQueued,
            bestDifficulty = Double.fromBits(prefs.getLong(KEY_BEST_DIFFICULTY, 0L)),
            blockTemplates = prefs.getLong(KEY_BLOCK_TEMPLATES, 0L),
        )
    }

    /**
     * Saves only counters whose current value is greater than the value on disk.
     * If no counter has increased, no write is performed.
     */
    fun save(status: MiningStatus) {
        val stored = get()
        val editor = prefs.edit()
        var hasChange = false

        if (status.acceptedShares > stored.acceptedShares) {
            editor.putLong(KEY_ACCEPTED_SHARES, status.acceptedShares)
            hasChange = true
        }
        if (status.rejectedShares > stored.rejectedShares) {
            editor.putLong(KEY_REJECTED_SHARES, status.rejectedShares)
            hasChange = true
        }
        if (status.identifiedShares > stored.identifiedShares) {
            editor.putLong(KEY_IDENTIFIED_SHARES, status.identifiedShares)
            hasChange = true
        }
        if (status.bestDifficulty > stored.bestDifficulty) {
            editor.putLong(KEY_BEST_DIFFICULTY, status.bestDifficulty.toRawBits())
            hasChange = true
        }
        if (status.blockTemplates > stored.blockTemplates) {
            editor.putLong(KEY_BLOCK_TEMPLATES, status.blockTemplates)
            hasChange = true
        }

        if (hasChange) {
            editor.apply()
        }
    }

    /**
     * Returns the last mining run duration in milliseconds. 0 if never saved.
     */
    fun getLastRunDurationMs(): Long = prefs.getLong(KEY_LAST_RUN_DURATION_MS, 0L)

    /**
     * Saves the last run duration. Called every minute while mining and on stop.
     * Pass 0 to clear (when user clicks Start Mining).
     */
    fun saveLastRunDuration(durationMs: Long) {
        prefs.edit().putLong(KEY_LAST_RUN_DURATION_MS, durationMs).apply()
    }

    /** Panel #1 idle display: last session share deltas when mining is not active (survives service destroy). */
    fun getLastStoppedSessionShareDisplay(): Triple<Long, Long, Long> = Triple(
        prefs.getLong(KEY_LAST_STOPPED_SESSION_DISPLAY_ACCEPTED, 0L),
        prefs.getLong(KEY_LAST_STOPPED_SESSION_DISPLAY_REJECTED, 0L),
        prefs.getLong(KEY_LAST_STOPPED_SESSION_DISPLAY_IDENTIFIED, 0L),
    )

    fun saveLastStoppedSessionShareDisplay(accepted: Long, rejected: Long, identified: Long) {
        prefs.edit()
            .putLong(KEY_LAST_STOPPED_SESSION_DISPLAY_ACCEPTED, accepted)
            .putLong(KEY_LAST_STOPPED_SESSION_DISPLAY_REJECTED, rejected)
            .putLong(KEY_LAST_STOPPED_SESSION_DISPLAY_IDENTIFIED, identified)
            .apply()
    }

    fun getLastStoppedSessionBestBlockDisplay(): Pair<Double, Long> = Pair(
        Double.fromBits(prefs.getLong(KEY_LAST_STOPPED_SESSION_DISPLAY_BEST_DIFFICULTY, 0L)),
        prefs.getLong(KEY_LAST_STOPPED_SESSION_DISPLAY_BLOCK_TEMPLATES_DELTA, 0L),
    )

    fun saveLastStoppedSessionBestBlockDisplay(bestDifficulty: Double, blockTemplatesDelta: Long) {
        prefs.edit()
            .putLong(KEY_LAST_STOPPED_SESSION_DISPLAY_BEST_DIFFICULTY, bestDifficulty.toRawBits())
            .putLong(KEY_LAST_STOPPED_SESSION_DISPLAY_BLOCK_TEMPLATES_DELTA, blockTemplatesDelta)
            .apply()
    }

    fun getTotalMiningTimeMs(): Long = prefs.getLong(KEY_TOTAL_MINING_TIME_MS, 0L)

    fun addTotalMiningTimeMs(deltaMs: Long) {
        val d = deltaMs.coerceAtLeast(0L)
        if (d == 0L) return
        prefs.edit().putLong(KEY_TOTAL_MINING_TIME_MS, prefs.getLong(KEY_TOTAL_MINING_TIME_MS, 0L) + d).apply()
    }

    /**
     * Persists a crash-recoverable mining-time checkpoint for the active session.
     * This does not mutate [KEY_TOTAL_MINING_TIME_MS]; callers reconcile via [reconcileMiningTimeCheckpoint].
     */
    fun saveMiningTimeCheckpoint(sessionStartMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val start = sessionStartMs.coerceAtLeast(0L)
        if (start <= 0L) return
        val baseTotalMs = prefs.getLong(KEY_TOTAL_MINING_TIME_MS, 0L).coerceAtLeast(0L)
        val elapsed = (nowMs - start).coerceAtLeast(0L)
        val checkpointTotal = baseTotalMs + elapsed
        prefs.edit()
            .putLong(KEY_MINING_TIME_CHECKPOINT_SESSION_START_MS, start)
            .putLong(KEY_MINING_TIME_CHECKPOINT_TOTAL_MS, checkpointTotal)
            .putLong(KEY_MINING_TIME_CHECKPOINT_SAVED_AT_MS, nowMs.coerceAtLeast(0L))
            .apply()
    }

    /**
     * Reconciles crash-safe checkpoint into [KEY_TOTAL_MINING_TIME_MS] using replacement semantics:
     * persisted total becomes max(existingTotal, checkpointTotal) to prevent double counting.
     * Returns the reconciled total.
     */
    fun reconcileMiningTimeCheckpoint(): Long {
        val existingTotal = prefs.getLong(KEY_TOTAL_MINING_TIME_MS, 0L).coerceAtLeast(0L)
        val checkpointTotal = prefs.getLong(KEY_MINING_TIME_CHECKPOINT_TOTAL_MS, 0L).coerceAtLeast(0L)
        val reconciled = maxOf(existingTotal, checkpointTotal)
        if (reconciled != existingTotal) {
            prefs.edit().putLong(KEY_TOTAL_MINING_TIME_MS, reconciled).apply()
        }
        clearMiningTimeCheckpoint()
        return reconciled
    }

    /** Clears in-progress mining-time checkpoint markers. */
    fun clearMiningTimeCheckpoint() {
        prefs.edit()
            .remove(KEY_MINING_TIME_CHECKPOINT_SESSION_START_MS)
            .remove(KEY_MINING_TIME_CHECKPOINT_TOTAL_MS)
            .remove(KEY_MINING_TIME_CHECKPOINT_SAVED_AT_MS)
            .apply()
    }

    /** `sessionMs == 0` means UI shows False. */
    fun getHeatStopSessionMs(): Long = prefs.getLong(KEY_HEAT_STOP_SESSION_MS, 0L)

    fun getHeatStopTempCelsius(): Float =
        Float.fromBits(prefs.getInt(KEY_HEAT_STOP_TEMP_CELSIUS_BITS, 0))

    fun setHeatStopSnapshot(sessionMs: Long, tempCelsius: Float) {
        prefs.edit()
            .putLong(KEY_HEAT_STOP_SESSION_MS, sessionMs.coerceAtLeast(0L))
            .putInt(KEY_HEAT_STOP_TEMP_CELSIUS_BITS, tempCelsius.toRawBits())
            .apply()
    }

    fun clearHeatStopForNewSession() {
        prefs.edit()
            .putLong(KEY_HEAT_STOP_SESSION_MS, 0L)
            .putInt(KEY_HEAT_STOP_TEMP_CELSIUS_BITS, HEAT_STOP_TEMP_INACTIVE_BITS)
            .apply()
    }

    /** Saves the latest chart snapshot for idle/crash restore. */
    fun saveChartSnapshot(
        cpu: List<Double>,
        gpu: List<Double>,
        elapsedSec: List<Float>,
        batteryTempC: List<Float>,
        donutCpuShares: Long,
        donutGpuShares: Long,
        sessionStartMs: Long,
        savedAtMs: Long = System.currentTimeMillis(),
    ) {
        val n = minOf(cpu.size, gpu.size, elapsedSec.size, batteryTempC.size)
        if (n <= 0) return

        val bounded = downsampleForSnapshot(
            cpu = cpu.subList(0, n),
            gpu = gpu.subList(0, n),
            elapsedSec = elapsedSec.subList(0, n),
            batteryTempC = batteryTempC.subList(0, n),
        )

        runCatching {
            val json = JSONObject().apply {
                put("v", CHART_SNAPSHOT_VERSION)
                put("sessionStartMs", sessionStartMs.coerceAtLeast(0L))
                put("savedAtMs", savedAtMs.coerceAtLeast(0L))
                put("donutCpuShares", donutCpuShares.coerceAtLeast(0L))
                put("donutGpuShares", donutGpuShares.coerceAtLeast(0L))
                put("cpu", JSONArray().apply { bounded.cpu.forEach { put(it.finiteOrZero()) } })
                put("gpu", JSONArray().apply { bounded.gpu.forEach { put(it.finiteOrZero()) } })
                put("elapsedSec", JSONArray().apply { bounded.elapsedSec.forEach { put(it.finiteOrZero()) } })
                // Preserve unavailable battery samples without writing forbidden NaN/Infinity to JSON.
                put("batteryTempC", JSONArray().apply {
                    bounded.batteryTempC.forEach {
                        if (it.isFinite()) put(it) else put(JSONObject.NULL)
                    }
                })
            }

            prefs.edit()
                .putString(KEY_CHART_SNAPSHOT_JSON, json.toString())
                .putLong(KEY_CHART_SNAPSHOT_SAVED_AT_MS, savedAtMs.coerceAtLeast(0L))
                .putLong(KEY_CHART_SNAPSHOT_SESSION_START_MS, sessionStartMs.coerceAtLeast(0L))
                .apply()
        }.onFailure { e ->
            AppLog.e(LOG_TAG) { "saveChartSnapshot failed: ${e.message}" }
        }
    }

    /** Returns latest chart snapshot if present and not stale. */
    fun getChartSnapshotOrNull(nowMs: Long = System.currentTimeMillis()): ChartSnapshot? {
        val raw = prefs.getString(KEY_CHART_SNAPSHOT_JSON, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val savedAtMs = json.optLong("savedAtMs", 0L).coerceAtLeast(0L)
        if (savedAtMs > 0L && nowMs - savedAtMs > CHART_SNAPSHOT_STALE_MS) return null

        val cpu = json.optJSONArray("cpu")?.toFiniteFloatList(defaultValue = 0f) ?: return null
        val gpu = json.optJSONArray("gpu")?.toFiniteFloatList(defaultValue = 0f) ?: return null
        val elapsedSec = json.optJSONArray("elapsedSec")?.toFiniteFloatList(defaultValue = 0f) ?: return null
        val batteryTempC = json.optJSONArray("batteryTempC")?.toBatteryTempFloatList() ?: return null
        val n = minOf(cpu.size, gpu.size, elapsedSec.size, batteryTempC.size)
        if (n <= 0) return null

        return ChartSnapshot(
            version = json.optInt("v", CHART_SNAPSHOT_VERSION),
            sessionStartMs = json.optLong("sessionStartMs", 0L).coerceAtLeast(0L),
            savedAtMs = savedAtMs,
            donutCpuShares = json.optLong("donutCpuShares", 0L).coerceAtLeast(0L),
            donutGpuShares = json.optLong("donutGpuShares", 0L).coerceAtLeast(0L),
            cpu = cpu.subList(0, n),
            gpu = gpu.subList(0, n),
            elapsedSec = elapsedSec.subList(0, n),
            batteryTempC = batteryTempC.subList(0, n),
        )
    }

    /** Clears latest chart snapshot. */
    fun clearChartSnapshot() {
        prefs.edit()
            .remove(KEY_CHART_SNAPSHOT_JSON)
            .remove(KEY_CHART_SNAPSHOT_SAVED_AT_MS)
            .remove(KEY_CHART_SNAPSHOT_SESSION_START_MS)
            .apply()
    }

    /**
     * Writes zeros for the five persisted counters. Used only when user resets via Config.
     */
    fun saveZeros() {
        prefs.edit()
            .putLong(KEY_ACCEPTED_SHARES, 0L)
            .putLong(KEY_REJECTED_SHARES, 0L)
            .putLong(KEY_IDENTIFIED_SHARES, 0L)
            .putLong(KEY_BEST_DIFFICULTY, 0.0.toRawBits())
            .putLong(KEY_BLOCK_TEMPLATES, 0L)
            .putLong(KEY_LIFETIME_SUM_SESSION_AVG_CPU_HS, 0.0.toRawBits())
            .putLong(KEY_LIFETIME_SUM_SESSION_AVG_GPU_HS, 0.0.toRawBits())
            .putLong(KEY_LIFETIME_TOTAL_NONCES, 0L)
            .putLong(KEY_LAST_STOPPED_SESSION_DISPLAY_ACCEPTED, 0L)
            .putLong(KEY_LAST_STOPPED_SESSION_DISPLAY_REJECTED, 0L)
            .putLong(KEY_LAST_STOPPED_SESSION_DISPLAY_IDENTIFIED, 0L)
            .putLong(KEY_LAST_STOPPED_SESSION_DISPLAY_BEST_DIFFICULTY, 0.0.toRawBits())
            .putLong(KEY_LAST_STOPPED_SESSION_DISPLAY_BLOCK_TEMPLATES_DELTA, 0L)
            .putLong(KEY_TOTAL_MINING_TIME_MS, 0L)
            .remove(KEY_CHART_SNAPSHOT_JSON)
            .remove(KEY_CHART_SNAPSHOT_SAVED_AT_MS)
            .remove(KEY_CHART_SNAPSHOT_SESSION_START_MS)
            .remove(KEY_MINING_TIME_CHECKPOINT_SESSION_START_MS)
            .remove(KEY_MINING_TIME_CHECKPOINT_TOTAL_MS)
            .remove(KEY_MINING_TIME_CHECKPOINT_SAVED_AT_MS)
            .apply()
    }

    /** Lifetime aggregates shown on dashboard page 2. */
    fun getLifetimeStats(): LifetimeMiningStats {
        return LifetimeMiningStats(
            sumSessionAvgCpuHs = Double.fromBits(prefs.getLong(KEY_LIFETIME_SUM_SESSION_AVG_CPU_HS, 0L)),
            sumSessionAvgGpuHs = Double.fromBits(prefs.getLong(KEY_LIFETIME_SUM_SESSION_AVG_GPU_HS, 0L)),
            totalNonces = prefs.getLong(KEY_LIFETIME_TOTAL_NONCES, 0L),
        )
    }

    /**
     * Called when a mining session ends. Adds [sessionNonces] to the lifetime nonce total.
     * Adds [sessionAvgCpuHs] / [sessionAvgGpuHs] to lifetime sums only if greater than [LIFETIME_HASHRATE_EPSILON].
     */
    fun addLifetimeSessionContribution(sessionNonces: Long, sessionAvgCpuHs: Double, sessionAvgGpuHs: Double) {
        val ed = prefs.edit()
        if (sessionNonces > 0) {
            ed.putLong(KEY_LIFETIME_TOTAL_NONCES, prefs.getLong(KEY_LIFETIME_TOTAL_NONCES, 0L) + sessionNonces)
        }
        if (sessionAvgCpuHs > LIFETIME_HASHRATE_EPSILON) {
            val cur = Double.fromBits(prefs.getLong(KEY_LIFETIME_SUM_SESSION_AVG_CPU_HS, 0L))
            ed.putLong(KEY_LIFETIME_SUM_SESSION_AVG_CPU_HS, (cur + sessionAvgCpuHs).toRawBits())
        }
        if (sessionAvgGpuHs > LIFETIME_HASHRATE_EPSILON) {
            val cur = Double.fromBits(prefs.getLong(KEY_LIFETIME_SUM_SESSION_AVG_GPU_HS, 0L))
            ed.putLong(KEY_LIFETIME_SUM_SESSION_AVG_GPU_HS, (cur + sessionAvgGpuHs).toRawBits())
        }
        ed.apply()
    }

    companion object {
        /** Ignore negligible averages when accumulating lifetime hash-rate sums. */
        const val LIFETIME_HASHRATE_EPSILON = 1e-6

        private const val PREFS_NAME = "mining_stats"
        private const val KEY_ACCEPTED_SHARES = "accepted_shares"
        private const val KEY_REJECTED_SHARES = "rejected_shares"
        private const val KEY_IDENTIFIED_SHARES = "identified_shares"
        private const val KEY_BEST_DIFFICULTY = "best_difficulty"
        private const val KEY_BLOCK_TEMPLATES = "block_templates"
        private const val KEY_LAST_RUN_DURATION_MS = "last_run_duration_ms"
        private const val KEY_LIFETIME_SUM_SESSION_AVG_CPU_HS = "lifetime_sum_session_avg_cpu_hs"
        private const val KEY_LIFETIME_SUM_SESSION_AVG_GPU_HS = "lifetime_sum_session_avg_gpu_hs"
        private const val KEY_LIFETIME_TOTAL_NONCES = "lifetime_total_nonces"
        private const val KEY_LAST_STOPPED_SESSION_DISPLAY_ACCEPTED = "last_stopped_session_display_accepted"
        private const val KEY_LAST_STOPPED_SESSION_DISPLAY_REJECTED = "last_stopped_session_display_rejected"
        private const val KEY_LAST_STOPPED_SESSION_DISPLAY_IDENTIFIED = "last_stopped_session_display_identified"
        private const val KEY_LAST_STOPPED_SESSION_DISPLAY_BEST_DIFFICULTY = "last_stopped_session_display_best_difficulty"
        private const val KEY_LAST_STOPPED_SESSION_DISPLAY_BLOCK_TEMPLATES_DELTA = "last_stopped_session_display_block_templates_delta"
        private const val KEY_TOTAL_MINING_TIME_MS = "total_mining_time_ms"
        private const val KEY_MINING_TIME_CHECKPOINT_SESSION_START_MS = "mining_time_checkpoint_session_start_ms"
        private const val KEY_MINING_TIME_CHECKPOINT_TOTAL_MS = "mining_time_checkpoint_total_ms"
        private const val KEY_MINING_TIME_CHECKPOINT_SAVED_AT_MS = "mining_time_checkpoint_saved_at_ms"
        private const val KEY_CHART_SNAPSHOT_JSON = "chart_snapshot_json"
        private const val KEY_CHART_SNAPSHOT_SAVED_AT_MS = "chart_snapshot_saved_at_ms"
        private const val KEY_CHART_SNAPSHOT_SESSION_START_MS = "chart_snapshot_session_start_ms"
        private const val CHART_SNAPSHOT_VERSION = 1
        private const val CHART_SNAPSHOT_MAX_POINTS = 1_200
        private const val CHART_SNAPSHOT_STALE_MS = 24 * 60 * 60 * 1000L
        private const val LOG_TAG = "MiningStatsRepository"
        private const val KEY_HEAT_STOP_SESSION_MS = "heat_stop_session_ms"
        private const val KEY_HEAT_STOP_TEMP_CELSIUS_BITS = "heat_stop_temp_c_bits"
        private val HEAT_STOP_TEMP_INACTIVE_BITS = Float.NaN.toRawBits()
    }

    private fun downsampleForSnapshot(
        cpu: List<Double>,
        gpu: List<Double>,
        elapsedSec: List<Float>,
        batteryTempC: List<Float>,
    ): ChartSnapshotSeries {
        if (cpu.size <= CHART_SNAPSHOT_MAX_POINTS) {
            return ChartSnapshotSeries(
                cpu = cpu.map { it.toFloat() },
                gpu = gpu.map { it.toFloat() },
                elapsedSec = elapsedSec.toList(),
                batteryTempC = batteryTempC.toList(),
            )
        }
        val step = ceil(cpu.size.toDouble() / CHART_SNAPSHOT_MAX_POINTS.toDouble()).toInt().coerceAtLeast(1)
        val last = cpu.lastIndex
        val keepIdx = ArrayList<Int>(CHART_SNAPSHOT_MAX_POINTS + 1)
        var i = 0
        while (i <= last) {
            keepIdx.add(i)
            i += step
        }
        if (keepIdx.lastOrNull() != last) keepIdx.add(last)
        return ChartSnapshotSeries(
            cpu = keepIdx.map { idx -> cpu[idx].toFloat() },
            gpu = keepIdx.map { idx -> gpu[idx].toFloat() },
            elapsedSec = keepIdx.map { idx -> elapsedSec[idx] },
            batteryTempC = keepIdx.map { idx -> batteryTempC[idx] },
        )
    }

    private fun JSONArray.toFiniteFloatList(defaultValue: Float): List<Float> {
        val out = ArrayList<Float>(length())
        for (i in 0 until length()) {
            val d = optDouble(i, defaultValue.toDouble())
            val f = d.toFloat()
            out.add(if (f.isFinite()) f else defaultValue)
        }
        return out
    }

    private fun JSONArray.toBatteryTempFloatList(): List<Float> {
        val out = ArrayList<Float>(length())
        for (i in 0 until length()) {
            if (isNull(i)) {
                out.add(Float.NaN)
                continue
            }
            val d = optDouble(i, Double.NaN)
            val f = d.toFloat()
            out.add(if (f.isFinite()) f else Float.NaN)
        }
        return out
    }

    private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f
}

data class LifetimeMiningStats(
    val sumSessionAvgCpuHs: Double,
    val sumSessionAvgGpuHs: Double,
    val totalNonces: Long,
) {
    val sumSessionAvgTotalHs: Double get() = sumSessionAvgCpuHs + sumSessionAvgGpuHs
}

data class ChartSnapshot(
    val version: Int,
    val sessionStartMs: Long,
    val savedAtMs: Long,
    val donutCpuShares: Long,
    val donutGpuShares: Long,
    val cpu: List<Float>,
    val gpu: List<Float>,
    val elapsedSec: List<Float>,
    val batteryTempC: List<Float>,
)

private data class ChartSnapshotSeries(
    val cpu: List<Float>,
    val gpu: List<Float>,
    val elapsedSec: List<Float>,
    val batteryTempC: List<Float>,
)
