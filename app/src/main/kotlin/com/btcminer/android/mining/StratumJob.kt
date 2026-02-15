package com.btcminer.android.mining

/**
 * Parsed Stratum job from mining.notify.
 * All hex strings are as received from the pool (e.g. prevhash is reversed per Bitcoin wire format).
 */
data class StratumJob(
    val jobId: String,
    val prevhashHex: String,
    val coinb1Hex: String,
    val coinb2Hex: String,
    val merkleBranchHex: List<String>,
    val versionHex: String,
    val nbitsHex: String,
    val ntimeHex: String,
    val cleanJobs: Boolean,
)
