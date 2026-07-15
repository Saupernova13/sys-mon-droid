package com.raavivi.sysmon.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.PowerReading
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.StatRow
import com.raavivi.sysmon.ui.common.UsageBar
import com.raavivi.sysmon.ui.common.formatWatts
import com.raavivi.sysmon.ui.theme.PowerColor
import java.util.Locale

/**
 * Dashboard card fed by the `power` block riding on every snapshot frame — the
 * multi-device envelope whose [PowerReading.headline] is the aggregate total.
 * Callers skip it entirely when the reading is null or unconfigured.
 */
@Composable
fun PowerCard(power: PowerReading, onOpen: () -> Unit) {
    val agg = power.headline
    SectionCard(
        title = "Power",
        accent = PowerColor,
        modifier = Modifier.clickable(onClick = onOpen),
        trailing = {
            val offline = agg.deviceCount - agg.onlineCount
            val badge = when {
                !power.available -> "unreachable"
                offline > 0 -> "$offline offline"
                agg.stale -> "stale"
                !agg.relayOn -> "all off"
                else -> null
            }
            badge?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    ) {
        if (!power.available) {
            Text(
                power.error ?: "No plug reachable",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatWatts(agg.watts),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = PowerColor,
            )
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                UsageBar(fraction = (agg.load.gaugePct / 100.0).toFloat(), color = PowerColor)
            }
        }
        Spacer(Modifier.height(10.dp))
        StatRow("Load", agg.load.label.ifBlank { "—" })
        StatRow("Today", "${power.currency}${fmt2(agg.cost.today)} · ${fmt2(agg.todayKwh)} kWh")
        if (power.devices.isEmpty()) {
            StatRow("Device", agg.deviceName.ifBlank { "—" })
        } else {
            power.devices.forEach { d ->
                StatRow(d.deviceName.ifBlank { d.id }, plugStatus(d))
            }
        }
    }
}

private fun plugStatus(d: PowerReading): String = when {
    !d.available -> "offline"
    !d.relayOn -> "off"
    else -> formatWatts(d.watts)
}

private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
