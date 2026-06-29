package com.btcminer.android.mining

/** Pure formatting for GPU chunk workMs average on the periodic Stats log line. */
internal fun formatAvgWorkMsSuffix(sumMs: Long, count: Long): String =
    if (count > 0) " avgWorkMs=${sumMs / count}" else ""
