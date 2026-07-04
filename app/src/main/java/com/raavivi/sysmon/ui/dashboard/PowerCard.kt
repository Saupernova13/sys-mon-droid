package com.raavivi.sysmon.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
 * Dashboard card fed by the `power` block riding on every snapshot frame.
 * Callers skip it entirely when the reading is null or unconfigured.
 */
@Composable
fun PowerCard(power: PowerReading, onOpen: () -> Unit) {
    SectionCard(
        title = "Power",
        accent = PowerColor,
        modifier = Modifier.clickable(onClick = onOpen),
        trailing = {
            val badge = when {
                !power.reachable -> "unreachable"
                power.stale -> "stale"
                !power.relayOn -> "relay off"
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
        if (!power.reachable) {
            Text(
                power.error ?: "Plug unreachable",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatWatts(power.watts),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = PowerColor,
            )
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                UsageBar(fraction = (power.load.gaugePct / 100.0).toFloat(), color = PowerColor)
            }
        }
        Spacer(Modifier.height(10.dp))
        StatRow("Load", power.load.label.ifBlank { "—" })
        StatRow("Today", "${power.currency}${fmt2(power.cost.today)} · ${fmt2(power.todayKwh)} kWh")
        StatRow("Device", power.deviceName.ifBlank { "—" })
    }
}

private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
