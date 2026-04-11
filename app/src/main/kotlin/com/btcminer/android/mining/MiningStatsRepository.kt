package com.btcminer.android.mining

import android.content.Context
import android.content.SharedPreferences

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
    }
}

data class LifetimeMiningStats(
    val sumSessionAvgCpuHs: Double,
    val sumSessionAvgGpuHs: Double,
    val totalNonces: Long,
) {
    val sumSessionAvgTotalHs: Double get() = sumSessionAvgCpuHs + sumSessionAvgGpuHs
}
