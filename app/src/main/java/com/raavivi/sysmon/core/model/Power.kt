package com.raavivi.sysmon.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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

// ── calendar ──────────────────────────────────────────────────────────────────

/**
 * `GET /api/power-usage/calendar?month=YYYY-MM[&plug=id]` — one entry per day
 * that recorded anything, rolled up from `power_hourly`. Days with no data are
 * simply absent from [days], so the UI fills the month grid itself.
 */
@Serializable
data class PowerCalendarResponse(
    /** `YYYY-MM`, echoed back (the server resolves a blank/invalid month to now). */
    val month: String = "",
    /** The plug this covers, or `"all"` for the aggregate. */
    val plug: String = "all",
    val currency: String = "",
    val effectiveRate: Double = 0.0,
    val days: List<PowerCalendarDay> = emptyList(),
    val monthKwh: Double = 0.0,
    val monthCost: Double = 0.0,
)

@Serializable
data class PowerCalendarDay(
    /** `YYYY-MM-DD`. */
    val date: String = "",
    val kwh: Double = 0.0,
    val cost: Double = 0.0,
    /** Average watts per hour of the day, always 24 long; `null` = the plug was
     *  offline that hour, which must read as a gap rather than as zero. */
    val hours: List<Double?> = emptyList(),
) {
    /** Day-of-month parsed off [date], or 0 when the string is malformed. */
    val dayOfMonth: Int get() = date.takeLast(2).toIntOrNull() ?: 0
}

// ── schedules ─────────────────────────────────────────────────────────────────

/**
 * One daily ON window for a plug: switch on at [on], off at [off], on the
 * weekdays in [days] (0 = Monday .. 6 = Sunday). A window may wrap past midnight
 * (`off` earlier than `on`, e.g. 22:00 -> 06:00).
 *
 * This type is both the decoded response row and the request body. The shared
 * JSON config has `encodeDefaults = false`, which would drop a window whose time
 * happened to equal the default here — and the server *rejects* a row with no
 * `on`/`off`, so that window would vanish on save. [EncodeDefault] pins every
 * field into the payload regardless.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PowerSchedule(
    /** Blank on a new row; the server assigns and de-duplicates ids. */
    @EncodeDefault val id: String = "",
    @EncodeDefault val plug: String = "",
    @EncodeDefault val label: String = "",
    /** `HH:mm`. */
    @EncodeDefault val on: String = "08:00",
    /** `HH:mm`. */
    @EncodeDefault val off: String = "18:00",
    @EncodeDefault val days: List<Int> = ALL_DAYS,
    @EncodeDefault val enabled: Boolean = true,
) {
    companion object {
        val ALL_DAYS = listOf(0, 1, 2, 3, 4, 5, 6)

        /** Weekday initials in backend order, for compact chips. */
        val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }
}

@Serializable
data class PowerSchedulesResponse(val schedules: List<PowerSchedule> = emptyList())

/** `POST /api/power/schedules` — replaces every window belonging to [plug]. */
@Serializable
data class PowerSchedulesBody(val plug: String, val schedules: List<PowerSchedule>)

// ── device manager ────────────────────────────────────────────────────────────

/**
 * `GET /api/power/devices` — the configured plug list as the admin UI sees it.
 * Passwords never leave the server; [hasPassword] is all the client learns.
 */
@Serializable
data class PowerDeviceRow(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val user: String = "",
    val enabled: Boolean = true,
    val hasPassword: Boolean = false,
    /** The automation API may never switch this plug (the dashboard still can). */
    val protected: Boolean = false,
    /** Push a phone notification for as long as this plug draws power. */
    val alert: Boolean = false,
    /** Appliance wording for the notification; blank = use [name]. */
    val alertLabel: String = "",
) {
    val display: String get() = name.ifBlank { id }
}

@Serializable
data class PowerDevicesResponse(val devices: List<PowerDeviceRow> = emptyList())

/**
 * `POST /api/power/devices` — the server replaces the whole list, so a patch has
 * to send every device back. Omitting a field keeps whatever the server stored:
 * `password` is always omitted (we only ever hold the masked flag), and the
 * alert fields are sent only by the screen that edits them.
 */
@Serializable
data class PowerDevicePatch(
    val id: String,
    val name: String,
    val url: String,
    val user: String,
    val enabled: Boolean,
    val protected: Boolean? = null,
    val alert: Boolean? = null,
    val alertLabel: String? = null,
)

@Serializable
data class PowerDevicesBody(val devices: List<PowerDevicePatch>)
