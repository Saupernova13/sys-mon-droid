package com.raavivi.sysmon.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** `GET /api/smart` / `POST /api/smart/rescan`. 404 when the widget is off. */
@Serializable
data class SmartResponse(
    val disks: List<SmartDisk> = emptyList(),
    /** Non-empty with [disks] populated means the last good reading is shown. */
    val error: String = "",
    val rescanned: Boolean = false,
    val probedAt: Double? = null,
    val installed: Boolean = false,
    /** "CrystalDiskInfo" or "smartctl". */
    val source: String = "",
    val notes: List<String> = emptyList(),
    val lastScanDay: String? = null,
)

@Serializable
data class SmartDisk(
    val id: String = "",
    val model: String = "",
    val serial: String = "",
    val firmware: String = "",
    @SerialName("interface") val interfaceName: String = "",
    val rotation: String = "",
    val size: String = "",
    val letters: List<String> = emptyList(),
    val health: String? = null,
    val healthCode: Int? = null,
    val state: String? = null,
    val temperatureC: Double? = null,
    val powerOnHours: Long? = null,
    val powerOnCount: Long? = null,
    val lifePct: Double? = null,
    val scannedAt: String? = null,
    /** Named readings; values are strings or numbers. */
    val values: JsonObject? = null,
    val attributes: JsonObject? = null,
    val thresholds: JsonObject? = null,
    /** Attribute ids at/below threshold; null when thresholds were unreadable. */
    val failing: List<String>? = null,
)
