package com.raavivi.sysmon.ui.power

import com.raavivi.sysmon.core.model.PowerHistoryResponse

/**
 * Folds an incremental `/api/power-usage/history` reply (asked with `start` = the last held
 * item's ts) into [held]: held items with ts >= [start] are replaced by the reply's (the last
 * bucket is recomputed), then items older than [cutoff] are dropped (null keeps everything).
 *
 * Returns null when the reply's bucket width or source differs from what is held, which means
 * the server did not honour `bucket_s` (an older server) or the data regime changed; the caller
 * then needs a full fetch.
 */
fun mergePowerHistory(
    held: PowerHistoryResponse,
    reply: PowerHistoryResponse,
    start: Double,
    cutoff: Double?,
): PowerHistoryResponse? {
    if (reply.bucketSeconds != held.bucketSeconds || reply.source != held.source) return null
    val items = (held.items.filter { it.ts < start } + reply.items)
        .filter { cutoff == null || it.ts >= cutoff }
    return reply.copy(items = items)
}
