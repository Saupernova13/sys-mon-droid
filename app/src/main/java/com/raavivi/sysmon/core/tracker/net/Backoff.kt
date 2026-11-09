package com.raavivi.sysmon.core.tracker.net

/** Retry schedule for failed uploads. */
object Backoff {
    /** Delay steps in seconds: 30 s, 1, 2, 5, then 10 min (the cap). */
    private val STEPS_S = longArrayOf(30, 60, 120, 300, 600)

    /** Wait applied when the server says Find Me is off (404). */
    const val FEATURE_OFF_MS: Long = 60L * 60L * 1000L

    /** Delay before the next attempt after [failures] consecutive failures (>= 1). */
    fun delayMs(failures: Int): Long {
        val i = (failures - 1).coerceIn(0, STEPS_S.size - 1)
        return STEPS_S[i] * 1000L
    }
}
