package com.raavivi.sysmon.core.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One plug that is currently drawing power, as the backend reported it. */
@Serializable
data class AlertPlug(
    val id: String = "",
    @SerialName("device_name") val deviceName: String = "",
    val label: String = "",
    val watts: Double = 0.0,
    @SerialName("cost_per_hour") val costPerHour: Double = 0.0,
    @SerialName("started_at") val startedAt: Double = 0.0,
) {
    /** What the notification calls this appliance. */
    val display: String
        get() = label.ifBlank { deviceName }.ifBlank { "appliance" }

    val startedAtMs: Long get() = (startedAt * 1000).toLong()
}

enum class AlertStyle { SEPARATE, COMBINED }

/**
 * A plug-alert push, parsed from its FCM data map.
 *
 * Every message carries the *complete* set of plugs that are on rather than one
 * plug's delta, so rendering never depends on state the app kept from an earlier
 * push — a message that arrived while the process was dead can't desync us. An
 * empty [plugs] means nothing is on: clear everything.
 */
data class PlugAlert(
    val event: String,
    val style: AlertStyle,
    val ongoing: Boolean,
    val currency: String,
    val plugs: List<AlertPlug>,
    val totalWatts: Double,
    val totalCostPerHour: Double,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parse a `type=plug_alert` data map. Returns null only if the payload is
         * unusable.
         *
         * A server that predates multi-plug alerts sends no `plugs` array, just
         * the single-plug keys; that shape is rebuilt into a one-element list
         * here so a phone on this build keeps working against an un-redeployed
         * server. (The reverse — an old app against a new server — is covered by
         * the server still emitting those same flat keys.)
         */
        fun from(data: Map<String, String>): PlugAlert? {
            val event = data["event"] ?: return null
            val plugs = parsePlugs(data, event)
            return PlugAlert(
                event = event,
                style = if (data["style"] == "combined") AlertStyle.COMBINED else AlertStyle.SEPARATE,
                ongoing = data["ongoing"] != "false", // default true
                currency = data["currency"].orEmpty(),
                plugs = plugs,
                // Fall back to summing the list: an old server sends no totals.
                totalWatts = data["total_watts"]?.toDoubleOrNull()
                    ?: plugs.sumOf { it.watts },
                totalCostPerHour = data["total_cost_per_hour"]?.toDoubleOrNull()
                    ?: plugs.sumOf { it.costPerHour },
            )
        }

        private fun parsePlugs(data: Map<String, String>, event: String): List<AlertPlug> {
            data["plugs"]?.let { raw ->
                return runCatching { json.decodeFromString<List<AlertPlug>>(raw) }
                    .getOrDefault(emptyList())
                    .filter { it.id.isNotBlank() }
            }
            // ── pre-multi-plug server ──
            if (event == "off") return emptyList()
            val id = data["plug_id"].orEmpty()
            if (id.isBlank()) return emptyList()
            return listOf(
                AlertPlug(
                    id = id,
                    deviceName = data["device_name"].orEmpty(),
                    label = data["label"].orEmpty(),
                    watts = data["watts"]?.toDoubleOrNull() ?: 0.0,
                    costPerHour = data["cost_per_hour"]?.toDoubleOrNull() ?: 0.0,
                    startedAt = data["started_at"]?.toDoubleOrNull() ?: 0.0,
                ),
            )
        }
    }
}
