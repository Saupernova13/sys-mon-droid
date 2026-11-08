package com.raavivi.sysmon.ui.more

import com.raavivi.sysmon.core.model.HostInfo

/** Host power delay when the server does not say (Windows applies 5 s). */
const val DEFAULT_POWER_DELAY_S = 5

/** "5 seconds", "1 minute", "2 minutes": whole minutes when [secs] divides by 60. */
fun describeDelay(secs: Int): String {
    val (n, unit) = if (secs >= 60 && secs % 60 == 0) (secs / 60) to "minute" else secs to "second"
    return "$n $unit${if (n == 1) "" else "s"}"
}

/** Confirm text such as "Restart Windows in 5 seconds?" (see the web's `powerPrompt`). */
fun powerPrompt(verb: String, host: HostInfo?): String {
    val target = if (host == null || host.isWindows) "Windows" else "this machine"
    val delay = host?.powerDelayS ?: DEFAULT_POWER_DELAY_S
    return "$verb $target in ${describeDelay(delay)}?"
}
