package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/power-usage` — smart-plug (Tasmota) reading. The multi-device
 * backend returns an envelope: top-level `available`/`configured` plus an
 * [aggregate] total and one entry per plug in [devices]; all three reuse this
 * same shape (the aggregate has no id/electricals, a device has no children).
 * Unconfigured/disabled keeps the flat `available=false` + [error] form. The
 * same object rides along inside [SystemSnapshot.power] on every snapshot/WS
 * frame.
 */
@Serializable
data class PowerReading(
    val id: String = "",
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
    val effectiveRate: Double = 0.0,
    val cost: PowerCost = PowerCost(),
    val device: PowerDevice = PowerDevice(),
    // Aggregate-only fields (the summed reading inside the envelope).
    val deviceCount: Int = 0,
    val onlineCount: Int = 0,
    // Envelope-only fields.
    val aggregate: PowerReading? = null,
    val devices: List<PowerReading> = emptyList(),
) {
    /** The reading a headline should show: the aggregate when this is the
     *  multi-device envelope, otherwise the reading itself. */
    val headline: PowerReading get() = aggregate ?: this

    fun deviceById(id: String): PowerReading? = devices.firstOrNull { it.id == id }
}

/** `POST /api/power/devices/{id}/relay` request body — the desired relay state. */
@Serializable
data class RelayBody(val on: Boolean)

/** `POST /api/power/devices/{id}/relay` response — the plug's reported state. */
@Serializable
data class RelayResponse(
    val id: String = "",
    val relayOn: Boolean = false,
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
