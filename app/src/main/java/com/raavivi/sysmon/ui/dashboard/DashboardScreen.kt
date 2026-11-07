package com.raavivi.sysmon.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.GpuDeviceStat
import com.raavivi.sysmon.core.model.SystemSnapshot
import com.raavivi.sysmon.ui.common.ChartSeries
import com.raavivi.sysmon.ui.common.Metric
import com.raavivi.sysmon.ui.common.PercentLineChart
import com.raavivi.sysmon.ui.common.Reading
import com.raavivi.sysmon.ui.common.Severity
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.StatRow
import com.raavivi.sysmon.ui.common.UsageBar
import com.raavivi.sysmon.ui.common.band
import com.raavivi.sysmon.ui.common.color
import com.raavivi.sysmon.ui.common.worst
import com.raavivi.sysmon.ui.common.formatBytes
import com.raavivi.sysmon.ui.common.formatMhz
import com.raavivi.sysmon.ui.common.formatPct
import com.raavivi.sysmon.ui.common.formatPct1
import com.raavivi.sysmon.ui.common.formatRate
import com.raavivi.sysmon.ui.common.formatTemp
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import com.raavivi.sysmon.ui.theme.SysMon

@Composable
fun DashboardScreen(onOpenPower: () -> Unit = {}) {
    val vm = rememberContainerViewModel { DashboardViewModel(it) }
    var menuOpen by remember { mutableStateOf(false) }
    val snap = vm.snapshot

    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(title = "Stats", actions = {
            ConnDot(vm.connection)
            Spacer(Modifier.size(8.dp))
            IconButton(onClick = vm::togglePause) {
                Icon(
                    if (vm.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (vm.paused) "Resume" else "Pause",
                )
            }
            IconButton(onClick = vm::refreshOnce) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Back up history on host") },
                        onClick = { menuOpen = false; vm.backupHistory() },
                    )
                }
            }
        })

        vm.backupMessage?.let { msg ->
            Text(
                msg,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { OverviewChartCard(vm) }
            if (snap != null) {
                item { CpuCard(snap) }
                item { RamCard(snap) }
                if (snap.gpu.available) {
                    snap.gpu.devices.forEach { dev -> item { GpuCard(dev) } }
                }
                item { DiskCard(snap) }
                snap.power?.takeIf { it.configured }?.let { p ->
                    item { PowerCard(p, onOpen = onOpenPower) }
                }
            } else {
                item {
                    Text(
                        vm.error ?: "Connecting to host…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnDot(state: ConnState) {
    val color = when (state) {
        ConnState.Live -> MaterialTheme.colorScheme.primary
        ConnState.Connecting -> MaterialTheme.colorScheme.tertiary
        ConnState.Paused -> MaterialTheme.colorScheme.onSurfaceVariant
        ConnState.Error -> MaterialTheme.colorScheme.error
    }
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}


@Composable
private fun OverviewChartCard(vm: DashboardViewModel) {
    val p = SysMon.palette
    SectionCard(title = "Utilisation (last ~10 min)") {
        PercentLineChart(
            series = listOf(
                ChartSeries("CPU", p.idCpu, vm.cpuHistory.toList()),
                ChartSeries("RAM", p.idMem, vm.ramHistory.toList()),
                ChartSeries("GPU", p.idGpu, vm.gpuHistory.toList()),
                ChartSeries("Disk", p.idDisk, vm.diskHistory.toList()),
            ),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("CPU", p.idCpu)
            Legend("RAM", p.idMem)
            Legend("GPU", p.idGpu)
            Legend("Disk", p.idDisk)
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    val shape = if (SysMon.palette.isInstrument) RoundedCornerShape(2.dp) else CircleShape
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(shape)
                .background(color),
        )
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The big number plus its meter. Classic: number left, meter beside it. Instrument
 * (web phone view): device label and number on one line, meter spanning underneath.
 */
@Composable
private fun ReadingMeter(
    label: String?,
    value: Double,
    state: Severity,
    identity: Color,
) {
    val fraction = (value / 100.0).toFloat()
    if (SysMon.palette.isInstrument) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    label.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Reading(formatPct1(value), state, identity)
            }
            Spacer(Modifier.height(6.dp))
            UsageBar(fraction = fraction, color = identity, barHeight = 10, state = state)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Reading(formatPct1(value), state, identity)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                UsageBar(fraction = fraction, color = identity, state = state)
            }
        }
    }
}

@Composable
private fun CpuCard(snap: SystemSnapshot) {
    val cpu = snap.cpu
    val p = SysMon.palette
    val load = band(Metric.CpuLoad, cpu.overallPct)
    val temp = band(Metric.CpuTemp, cpu.tempCelsius)
    SectionCard(title = "CPU", accent = p.idCpu, state = worst(load, temp)) {
        ReadingMeter("Total load", cpu.overallPct, load, p.idCpu)
        Spacer(Modifier.height(10.dp))
        if (cpu.cores.isNotEmpty()) {
            CoreBars(cpu.cores.map { it.usagePct.toFloat() }, p.idCpu)
            Spacer(Modifier.height(10.dp))
        }
        StatRow("Cores", cpu.cores.size.toString())
        StatRow("Frequency", formatMhz(cpu.freqCurrentMhz))
        StatRow(
            "Temperature",
            formatTemp(cpu.tempCelsius),
            valueColor = tempInk(temp, cpu.tempCelsius),
        )
    }
}

/** Value ink for a temperature row: severity colour only once past Nominal. */
@Composable
private fun tempInk(state: Severity, temp: Double?): Color =
    if (temp != null && state != Severity.Nominal) state.color(Color.Unspecified) else Color.Unspecified

@Composable
private fun CoreBars(cores: List<Float>, color: Color) {
    val p = SysMon.palette
    val track = if (p.isInstrument) p.track else color.copy(alpha = 0.25f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(40.dp),
    ) {
        if (cores.isEmpty()) return@Canvas
        val gap = 3f
        val barW = (size.width - gap * (cores.size - 1)) / cores.size
        cores.forEachIndexed { i, v ->
            val h = size.height * (v.coerceIn(0f, 100f) / 100f)
            val x = i * (barW + gap)
            drawRect(
                color = track,
                topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                size = androidx.compose.ui.geometry.Size(barW, size.height),
            )
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(x, size.height - h),
                size = androidx.compose.ui.geometry.Size(barW, h),
            )
        }
    }
}

@Composable
private fun RamCard(snap: SystemSnapshot) {
    val ram = snap.ram
    val p = SysMon.palette
    val used = band(Metric.RamUsed, ram.usagePct)
    val swap = if (ram.swapTotalBytes > 0) band(Metric.RamSwap, ram.swapPct) else Severity.Nominal
    SectionCard(title = "Memory", accent = p.idMem, state = worst(used, swap)) {
        ReadingMeter("Used", ram.usagePct, used, p.idMem)
        Spacer(Modifier.height(10.dp))
        StatRow("Used", "${formatBytes(ram.usedBytes)} / ${formatBytes(ram.totalBytes)}")
        StatRow("Available", formatBytes(ram.availableBytes))
        StatRow(
            "Swap",
            "${formatBytes(ram.swapUsedBytes)} / ${formatBytes(ram.swapTotalBytes)} (${formatPct(ram.swapPct)})",
            valueColor = tempInk(swap, ram.swapPct),
        )
    }
}

@Composable
private fun GpuCard(dev: GpuDeviceStat) {
    val title = if (dev.isIgpu) "GPU (integrated)" else "GPU"
    val p = SysMon.palette
    val load = band(Metric.GpuLoad, dev.usagePct)
    val temp = band(Metric.GpuTemp, dev.tempCelsius)
    SectionCard(title = title, accent = p.idGpu, state = worst(load, temp)) {
        Text(dev.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        ReadingMeter(if (p.isInstrument) "Load" else null, dev.usagePct, load, p.idGpu)
        Spacer(Modifier.height(10.dp))
        StatRow(
            "VRAM",
            "${formatBytes((dev.memUsedMb * 1024 * 1024).toLong())} / ${formatBytes((dev.memTotalMb * 1024 * 1024).toLong())} (${formatPct(dev.memPct)})",
        )
        StatRow(
            "Temperature",
            formatTemp(dev.tempCelsius),
            valueColor = tempInk(temp, dev.tempCelsius),
        )
    }
}

@Composable
private fun DiskCard(snap: SystemSnapshot) {
    val p = SysMon.palette
    val states = snap.disk.drives.map { band(Metric.DiskUsed, it.usagePct) }
    SectionCard(title = "Disks", accent = p.idDisk, state = worst(*states.toTypedArray())) {
        snap.disk.drives.forEachIndexed { i, d ->
            val state = states[i]
            if (i > 0) Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    d.mountpoint.ifBlank { d.device },
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    formatPct(d.usagePct),
                    fontFamily = if (p.monoNumbers) FontFamily.Monospace else null,
                    color = if (state == Severity.Nominal) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        state.color(p.idDisk)
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            UsageBar(
                fraction = (d.usagePct / 100.0).toFloat(),
                color = p.idDisk,
                barHeight = if (p.isInstrument) 6 else 8,
                state = state,
            )
            Spacer(Modifier.height(6.dp))
            StatRow("Free", "${formatBytes(d.freeBytes)} of ${formatBytes(d.totalBytes)}")
            StatRow("I/O", "R ${formatRate(d.readBytesSec)} · W ${formatRate(d.writeBytesSec)}")
        }
    }
}
