package com.raavivi.sysmon.ui.power

import com.raavivi.sysmon.core.model.PowerHistoryResponse

/**
 * Folds an incremental `/api/power-usage/history` reply (asked with `start` = the last held
 * item's ts) into [held]: held items with ts >= [start] are replaced by the reply's (the last
 * bucket is recomputed), then items older than [cutoff] are dropped (null keeps everything).
 *
 * Returns null when the reply's bucket width differs from what is held, which means the server
 * did not honour `bucket_s` (an older server); the caller then needs a full fetch. `source` is the
 * data tier and may legitimately differ (a long range's full fetch is "hourly", the short
 * incremental window is "raw"), so it is not compared.
 */
fun mergePowerHistory(
    held: PowerHistoryResponse,
    reply: PowerHistoryResponse,
    start: Double,
    cutoff: Double?,
): PowerHistoryResponse? {
    if (reply.bucketSeconds != held.bucketSeconds) return null
    val items = (held.items.filter { it.ts < start } + reply.items)
        .filter { cutoff == null || it.ts >= cutoff }
    return reply.copy(items = items)
}
