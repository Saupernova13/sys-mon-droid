package com.raavivi.sysmon.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.raavivi.sysmon.ui.theme.SysMon

/** Reading condition; port of sys-mon `frontend/js/severity.js`. Ordered by rank. */
enum class Severity(val label: String) {
    Offline("OFFLINE"),
    Nominal("NOMINAL"),
    Caution("CAUTION"),
    Warning("WARNING"),
}

/** Thresholds per metric. A null [warning] means the metric never reaches Warning. */
enum class Metric(val caution: Double, val warning: Double?) {
    CpuLoad(92.0, null),
    GpuLoad(95.0, null),
    RamUsed(85.0, 95.0),
    RamSwap(40.0, 75.0),
    DiskUsed(85.0, 94.0),
    CpuTemp(82.0, 94.0),
    GpuTemp(80.0, 90.0),
    DiskTempHdd(50.0, 60.0),
    DiskTempSsd(70.0, 80.0),
}

fun band(metric: Metric, value: Double?): Severity {
    if (value == null || !value.isFinite()) return Severity.Nominal
    val warning = metric.warning
    return when {
        warning != null && value >= warning -> Severity.Warning
        value >= metric.caution -> Severity.Caution
        else -> Severity.Nominal
    }
}

fun diskTempMetric(mediaType: String?, rotational: Boolean?): Metric = when {
    rotational == true -> Metric.DiskTempHdd
    rotational == false -> Metric.DiskTempSsd
    mediaType == "HDD" -> Metric.DiskTempHdd
    else -> Metric.DiskTempSsd
}

fun healthState(health: String?): Severity = when (health) {
    "good" -> Severity.Nominal
    "caution" -> Severity.Caution
    "bad" -> Severity.Warning
    else -> Severity.Offline
}

/** Highest-ranked state; Nominal when none are given. */
fun worst(vararg states: Severity): Severity = states.maxOrNull() ?: Severity.Nominal

/** Ink for a reading: identity (Classic) or nominal ink (Instrument) when Nominal. */
@Composable
fun Severity.color(identity: Color): Color {
    val p = SysMon.palette
    return when (this) {
        Severity.Nominal -> if (p.isInstrument) p.nominalInk else identity
        Severity.Caution -> p.sigWarn
        Severity.Warning -> p.sigCrit
        Severity.Offline -> p.offline
    }
}

/** Meter fill: identity when Nominal in both themes, otherwise as [color]. */
@Composable
fun Severity.meterColor(identity: Color): Color =
    if (this == Severity.Nominal) identity else color(identity)
