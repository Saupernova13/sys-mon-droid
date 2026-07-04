package com.raavivi.sysmon.ui.power

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.PowerReading
import com.raavivi.sysmon.ui.common.ChartSeries
import com.raavivi.sysmon.ui.common.ErrorBox
import com.raavivi.sysmon.ui.common.LoadingBox
import com.raavivi.sysmon.ui.common.PercentLineChart
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.StatRow
import com.raavivi.sysmon.ui.common.UsageBar
import com.raavivi.sysmon.ui.common.formatDateTime
import com.raavivi.sysmon.ui.common.formatWatts
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import com.raavivi.sysmon.ui.theme.PowerColor
import java.util.Locale

@Composable
fun PowerScreen(onBack: () -> Unit) {
    val vm = rememberContainerViewModel { PowerViewModel(it) }

    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(title = "Power", actions = {
            IconButton(onClick = vm::refresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        })

        when {
            vm.loading && vm.reading == null -> LoadingBox()
            vm.error != null && vm.reading == null -> ErrorBox(vm.error!!, onRetry = vm::refresh)
            else -> vm.reading?.let { PowerDetail(vm, it) }
        }
    }
}

@Composable
private fun PowerDetail(vm: PowerViewModel, p: PowerReading) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!p.reachable) {
            SectionCard(title = "Plug offline", accent = MaterialTheme.colorScheme.error) {
                Text(p.error ?: "The smart plug is not reachable.")
            }
            return@Column
        }

        SectionCard(title = p.deviceName.ifBlank { "Live reading" }, accent = PowerColor) {
            Row {
                Text(
                    formatWatts(p.watts),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = PowerColor,
                )
            }
            Spacer(Modifier.height(8.dp))
            UsageBar(fraction = (p.load.gaugePct / 100.0).toFloat(), color = PowerColor)
            Spacer(Modifier.height(10.dp))
            StatRow("Load", "${p.load.label} — ${p.load.hint}")
            StatRow("Relay", if (p.relayOn) "on" else "off")
            if (p.stale) StatRow("Reading", "stale (${p.staleSeconds?.toInt() ?: "?"}s old)")
            StatRow("As of", formatDateTime(p.ts))
        }

        SectionCard(title = "Usage (${vm.range.label})", accent = PowerColor) {
            val hist = vm.history
            if (hist == null || hist.items.size < 2) {
                Text(
                    "Not enough history yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // The shared chart plots 0..100, so scale watts to the plug's
                // gauge ceiling (fall back to the series peak).
                val maxW = hist.gaugeMaxW.takeIf { it > 0 }
                    ?: (hist.items.mapNotNull { it.watts }.maxOrNull() ?: 1.0)
                val pts = hist.items.map {
                    (((it.watts ?: 0.0) / maxW) * 100.0).toFloat()
                }
                PercentLineChart(series = listOf(ChartSeries("W", PowerColor, pts)))
                Spacer(Modifier.height(6.dp))
                Text(
                    "peak ${formatWatts(hist.items.mapNotNull { it.watts }.maxOrNull() ?: 0.0)} · scale ${formatWatts(maxW)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PowerRange.entries.forEach { r ->
                    FilterChip(
                        selected = vm.range == r,
                        onClick = { vm.select(r) },
                        label = { Text(r.label) },
                    )
                }
            }
        }

        SectionCard(title = "Cost", accent = PowerColor) {
            val c = p.currency
            StatRow("Today", "$c${fmt2(p.cost.today)} · ${fmt2(p.todayKwh)} kWh")
            StatRow("Yesterday", "$c${fmt2(p.cost.yesterday)} · ${fmt2(p.yesterdayKwh)} kWh")
            p.cost.projectedToday?.let { proj ->
                val vs = p.cost.projectedVsYesterdayPct?.let {
                    String.format(Locale.US, " (%+.0f%% vs yesterday)", it)
                } ?: ""
                StatRow("Projected today", "$c${fmt2(proj)}$vs")
            }
            StatRow("At this draw", "$c${fmt2(p.cost.perHour)}/h · $c${fmt2(p.cost.perDay)}/day · $c${fmt2(p.cost.perMonth)}/month")
            StatRow("Total", "$c${fmt2(p.cost.total)} · ${fmt2(p.totalKwh)} kWh")
            StatRow("Tariff", "$c${fmt2(p.tariff)}/kWh")
        }

        SectionCard(title = "Electrical", accent = PowerColor) {
            StatRow("Voltage", "${fmt1(p.voltage)} V (${p.quality.voltage.label})")
            StatRow("Current", "${String.format(Locale.US, "%.3f", p.current)} A")
            StatRow("Power factor", "${fmt2(p.powerFactor)} (${p.quality.powerFactor.label})")
            StatRow("Apparent", "${fmt1(p.apparentVa)} VA")
            StatRow("Reactive", "${fmt1(p.reactiveVar)} var (${fmt1(p.quality.reactivePct)}%)")
        }

        SectionCard(title = "Device") {
            p.device.firmware?.let { StatRow("Firmware", it) }
            p.device.wifiSignalDbm?.let { StatRow("Wi-Fi", "${it.toInt()} dBm") }
            p.device.uptime?.let { StatRow("Uptime", it) }
            StatRow("Address", p.device.ip.ifBlank { "—" })
        }
    }
}

private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
