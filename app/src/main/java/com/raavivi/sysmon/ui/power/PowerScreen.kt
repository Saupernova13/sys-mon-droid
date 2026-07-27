package com.raavivi.sysmon.ui.power

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raavivi.sysmon.LocalAppContainer
import com.raavivi.sysmon.core.auth.SessionManager
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
import kotlinx.coroutines.delay

@Composable
fun PowerScreen(onBack: () -> Unit) {
    val vm = rememberContainerViewModel { PowerViewModel(it) }

    vm.relayError?.let { LaunchedEffect(it) { delay(4000); vm.clearRelayError() } }

    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(title = "Power", actions = {
            IconButton(onClick = vm::refresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        })

        vm.relayError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        when {
            vm.loading && vm.envelope == null -> LoadingBox()
            vm.error != null && vm.envelope == null -> ErrorBox(vm.error!!, onRetry = vm::refresh)
            else -> vm.envelope?.let { PowerDetail(vm, it) }
        }
    }
}

@Composable
private fun PowerDetail(vm: PowerViewModel, env: PowerReading) {
    val container = LocalAppContainer.current
    val role by container.session.role.collectAsStateWithLifecycle()
    val isAdmin = role == SessionManager.ROLE_ADMIN

    val calendarVm = rememberContainerViewModel { PowerCalendarViewModel(it) }
    val schedulesVm = rememberContainerViewModel { PowerSchedulesViewModel(it) }

    // Each tab fetches only once it is actually on screen, and follows the plug
    // selection shared across all three.
    LaunchedEffect(vm.tab, vm.selected) {
        when (vm.tab) {
            PowerTab.Calendar -> calendarVm.setPlug(vm.selected)
            PowerTab.Schedules -> schedulesVm.setPlug(vm.schedulePlug(env))
            PowerTab.Live -> Unit
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!env.configured || env.devices.isEmpty()) {
            SectionCard(title = "No plugs", accent = MaterialTheme.colorScheme.error) {
                Text(env.error ?: "No smart plugs are configured on the server.")
            }
            return@Column
        }

        DevicesCard(vm, env, isAdmin)
        SelectorChips(vm, env)
        TabBar(vm)

        when (vm.tab) {
            PowerTab.Live -> LiveTab(vm, env)
            PowerTab.Calendar -> PowerCalendarTab(calendarVm)
            PowerTab.Schedules -> PowerSchedulesTab(
                vm = schedulesVm,
                plug = vm.schedulePlug(env),
                plugName = vm.schedulePlugName(env),
                isAdmin = isAdmin,
            )
        }
    }
}

@Composable
private fun TabBar(vm: PowerViewModel) {
    TabRow(selectedTabIndex = vm.tab.ordinal, containerColor = MaterialTheme.colorScheme.background) {
        PowerTab.entries.forEach { t ->
            Tab(
                selected = vm.tab == t,
                onClick = { vm.tab = t },
                text = { Text(t.label, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}

@Composable
private fun LiveTab(vm: PowerViewModel, env: PowerReading) {
    val d = vm.detail() ?: return
    val isAll = vm.selected == PowerViewModel.ALL

    if (!isAll && !d.available) {
        SectionCard(title = d.deviceName.ifBlank { "Plug" }, accent = MaterialTheme.colorScheme.error) {
            Text(d.error ?: "The smart plug is not reachable.")
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeadlineCard(d, isAll)
        HistoryCard(vm)
        CostCard(d, env.currency)

        if (!isAll) {
            SectionCard(title = "Electrical", accent = PowerColor) {
                StatRow("Voltage", "${fmt1(d.voltage)} V (${d.quality.voltage.label})")
                StatRow("Current", "${String.format(Locale.US, "%.3f", d.current)} A")
                StatRow("Power factor", "${fmt2(d.powerFactor)} (${d.quality.powerFactor.label})")
                StatRow("Apparent", "${fmt1(d.apparentVa)} VA")
                StatRow("Reactive", "${fmt1(d.reactiveVar)} var (${fmt1(d.quality.reactivePct)}%)")
            }

            SectionCard(title = "Device") {
                d.device.firmware?.let { StatRow("Firmware", it) }
                d.device.wifiSignalDbm?.let { StatRow("Wi-Fi", "${it.toInt()} dBm") }
                d.device.uptime?.let { StatRow("Uptime", it) }
                StatRow("Address", d.device.ip.ifBlank { "—" })
            }
        }
    }
}

/** Every plug with its live draw and an admin-only relay switch. */
@Composable
private fun DevicesCard(vm: PowerViewModel, env: PowerReading, isAdmin: Boolean) {
    val agg = env.headline
    SectionCard(
        title = "Devices",
        accent = PowerColor,
        trailing = {
            if (agg.deviceCount > 0) {
                Text(
                    "${agg.onlineCount}/${agg.deviceCount} online",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        env.devices.forEach { d ->
            val pending = vm.relayPending[d.id]
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(d.deviceName.ifBlank { d.id }, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        deviceStatus(d),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (d.available) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    )
                }
                if (d.available && d.relayOn) {
                    Text(
                        formatWatts(d.watts),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = PowerColor,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = pending ?: d.relayOn,
                    onCheckedChange = { vm.toggleRelay(d.id, it) },
                    enabled = isAdmin && d.available && pending == null,
                )
            }
        }
        if (!isAdmin) {
            Text(
                "Switching plugs requires an admin account.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectorChips(vm: PowerViewModel, env: PowerReading) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = vm.selected == PowerViewModel.ALL,
            onClick = { vm.select(PowerViewModel.ALL) },
            label = { Text("All") },
        )
        env.devices.forEach { d ->
            FilterChip(
                selected = vm.selected == d.id,
                onClick = { vm.select(d.id) },
                label = { Text(d.deviceName.ifBlank { d.id }) },
            )
        }
    }
}

@Composable
private fun HeadlineCard(d: PowerReading, isAll: Boolean) {
    val title = if (isAll) "All plugs" else d.deviceName.ifBlank { "Live reading" }
    SectionCard(title = title, accent = PowerColor) {
        Row {
            Text(
                formatWatts(d.watts),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = PowerColor,
            )
        }
        Spacer(Modifier.height(8.dp))
        UsageBar(fraction = (d.load.gaugePct / 100.0).toFloat(), color = PowerColor)
        Spacer(Modifier.height(10.dp))
        StatRow("Load", listOf(d.load.label, d.load.hint).filter { it.isNotBlank() }.joinToString(" — "))
        if (isAll) {
            StatRow("Online", "${d.onlineCount} of ${d.deviceCount} plugs")
        } else {
            StatRow("Relay", if (d.relayOn) "on" else "off")
        }
        if (d.stale) StatRow("Reading", "stale (${d.staleSeconds?.toInt() ?: "?"}s old)")
        if (d.ts > 0) StatRow("As of", formatDateTime(d.ts))
    }
}

@Composable
private fun HistoryCard(vm: PowerViewModel) {
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
                    onClick = { vm.selectRange(r) },
                    label = { Text(r.label) },
                )
            }
        }
    }
}

@Composable
private fun CostCard(d: PowerReading, currency: String) {
    SectionCard(title = "Cost", accent = PowerColor) {
        StatRow("Today", "$currency${fmt2(d.cost.today)} · ${fmt2(d.todayKwh)} kWh")
        StatRow("Yesterday", "$currency${fmt2(d.cost.yesterday)} · ${fmt2(d.yesterdayKwh)} kWh")
        d.cost.projectedToday?.let { proj ->
            val vs = d.cost.projectedVsYesterdayPct?.let {
                String.format(Locale.US, " (%+.0f%% vs yesterday)", it)
            } ?: ""
            StatRow("Projected today", "$currency${fmt2(proj)}$vs")
        }
        StatRow("At this draw", "$currency${fmt2(d.cost.perHour)}/h · $currency${fmt2(d.cost.perDay)}/day · $currency${fmt2(d.cost.perMonth)}/month")
        StatRow("Total", "$currency${fmt2(d.cost.total)} · ${fmt2(d.totalKwh)} kWh")
        if (d.tariff > 0) StatRow("Tariff", "$currency${fmt2(d.tariff)}/kWh")
    }
}

private fun deviceStatus(d: PowerReading): String = when {
    !d.available -> d.error ?: "offline"
    d.stale -> "stale reading"
    !d.relayOn -> "switched off"
    else -> d.load.label.ifBlank { "on" }
}

private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
