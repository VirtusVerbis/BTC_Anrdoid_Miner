package com.btcminer.android.mining

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists five dashboard counters (accepted/rejected/identified shares, best difficulty, block templates)
 * to a plain SharedPreferences file, separate from the encrypted config vault.
 * Nonces are not persisted (per-round only). Values are only written when
 * current is greater than stored (monotonic), except on reset which writes zeros.
 */
class MiningStatsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Reads the five persisted counters and returns them as a [MiningStatus] with [MiningStatus.State.Idle].
     * Nonces are not persisted; noncesScanned is always 0.
     */
    fun get(): MiningStatus {
        return MiningStatus(
            state = MiningStatus.State.Idle,
            noncesScanned = 0L,
            acceptedShares = prefs.getLong(KEY_ACCEPTED_SHARES, 0L),
            rejectedShares = prefs.getLong(KEY_REJECTED_SHARES, 0L),
            identifiedShares = prefs.getLong(KEY_IDENTIFIED_SHARES, 0L),
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
     * Writes zeros for the five persisted counters. Used only when user resets via Config.
     */
    fun saveZeros() {
        prefs.edit()
            .putLong(KEY_ACCEPTED_SHARES, 0L)
            .putLong(KEY_REJECTED_SHARES, 0L)
            .putLong(KEY_IDENTIFIED_SHARES, 0L)
            .putLong(KEY_BEST_DIFFICULTY, 0.0.toRawBits())
            .putLong(KEY_BLOCK_TEMPLATES, 0L)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "mining_stats"
        private const val KEY_ACCEPTED_SHARES = "accepted_shares"
        private const val KEY_REJECTED_SHARES = "rejected_shares"
        private const val KEY_IDENTIFIED_SHARES = "identified_shares"
        private const val KEY_BEST_DIFFICULTY = "best_difficulty"
        private const val KEY_BLOCK_TEMPLATES = "block_templates"
    }
}
