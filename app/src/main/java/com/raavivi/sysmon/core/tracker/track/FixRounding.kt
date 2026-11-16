package com.raavivi.sysmon.core.tracker.track

import com.raavivi.sysmon.core.tracker.model.Fix
import kotlin.math.roundToLong

/** Rounds to [decimals] places; the float-to-double widening of Android values otherwise leaks noise like 9.647000312805176. */
fun roundTo(v: Double, decimals: Int): Double {
    var f = 1.0
    repeat(decimals) { f *= 10 }
    return (v * f).roundToLong() / f
}

/** Trims the sensor fields to useful precision (lat/lon and ts are left alone). */
fun Fix.rounded(): Fix = copy(
    accuracyM = accuracyM?.let { roundTo(it, 1) },
    altitudeM = altitudeM?.let { roundTo(it, 1) },
    speedMps = speedMps?.let { roundTo(it, 2) },
    bearingDeg = bearingDeg?.let { roundTo(it, 1) },
)
