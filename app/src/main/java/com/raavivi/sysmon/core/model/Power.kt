package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/power-usage` — smart-plug (Tasmota) reading. Three states:
 * disabled/unconfigured (`available=false` + [error]), live, and stale
 * (`stale=true` + [staleSeconds]). The same object rides along inside
 * [SystemSnapshot.power] on every snapshot/WS frame.
 */
@Serializable
data class PowerReading(
    val available: Boolean = false,
    val reachable: Boolean = false,
    val configured: Boolean = false,
    val stale: Boolean = false,
    val staleSeconds: Double? = null,
    val error: String? = null,
    val ts: Double = 0.0,
    val deviceName: String = "",
    val relayOn: Boolean = true,
    val watts: Double = 0.0,
    val apparentVa: Double = 0.0,
    val reactiveVar: Double = 0.0,
    val powerFactor: Double = 0.0,
    val voltage: Double = 0.0,
    val current: Double = 0.0,
    val todayKwh: Double = 0.0,
    val yesterdayKwh: Double = 0.0,
    val totalKwh: Double = 0.0,
    val totalStart: String? = null,
    val load: PowerLoad = PowerLoad(),
    val quality: PowerQuality = PowerQuality(),
    val tariff: Double = 0.0,
    val currency: String = "",
    val cost: PowerCost = PowerCost(),
    val device: PowerDevice = PowerDevice(),
)

@Serializable
data class PowerLoad(
    val level: String = "",
    val label: String = "",
    val hint: String = "",
    val gaugePct: Double = 0.0,
)

@Serializable
data class PowerQuality(
    val powerFactor: QualityBand = QualityBand(),
    val voltage: QualityBand = QualityBand(),
    val reactivePct: Double = 0.0,
)

@Serializable
data class QualityBand(
    val label: String = "",
    val level: String = "",
    val desc: String = "",
)

@Serializable
data class PowerCost(
    val perHour: Double = 0.0,
    val perDay: Double = 0.0,
    val perMonth: Double = 0.0,
    val today: Double = 0.0,
    val yesterday: Double = 0.0,
    val total: Double = 0.0,
    val projectedTodayKwh: Double? = null,
    val projectedToday: Double? = null,
    val projectedVsYesterdayPct: Double? = null,
)

/** Raw Tasmota status fields; kept nullable since they pass through unvalidated. */
@Serializable
data class PowerDevice(
    val firmware: String? = null,
    val wifiRssi: Double? = null,
    val wifiSignalDbm: Double? = null,
    val uptime: String? = null,
    val ip: String = "",
)

/** One `/api/power-usage/history` sample; `ts` is integer in bucketed mode. */
@Serializable
data class PowerHistoryItem(
    val ts: Double = 0.0,
    val watts: Double? = null,
    val voltage: Double? = null,
    val pf: Double? = null,
)

@Serializable
data class PowerHistoryResponse(
    val items: List<PowerHistoryItem> = emptyList(),
    val bucketSeconds: Long = 0,
    val tariff: Double = 0.0,
    val currency: String = "",
    val gaugeMaxW: Double = 0.0,
)
