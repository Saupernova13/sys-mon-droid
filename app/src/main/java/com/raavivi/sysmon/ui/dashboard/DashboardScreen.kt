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
import com.raavivi.sysmon.ui.common.PercentLineChart
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.StatRow
import com.raavivi.sysmon.ui.common.UsageBar
import com.raavivi.sysmon.ui.common.formatBytes
import com.raavivi.sysmon.ui.common.formatMhz
import com.raavivi.sysmon.ui.common.formatPct
import com.raavivi.sysmon.ui.common.formatPct1
import com.raavivi.sysmon.ui.common.formatRate
import com.raavivi.sysmon.ui.common.formatTemp
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import com.raavivi.sysmon.ui.theme.CpuColor
import com.raavivi.sysmon.ui.theme.DiskColor
import com.raavivi.sysmon.ui.theme.GpuColor
import com.raavivi.sysmon.ui.theme.RamColor

@Composable
fun DashboardScreen() {
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
    SectionCard(title = "Utilisation (last ~10 min)") {
        PercentLineChart(
            series = listOf(
                ChartSeries("CPU", CpuColor, vm.cpuHistory.toList()),
                ChartSeries("RAM", RamColor, vm.ramHistory.toList()),
                ChartSeries("GPU", GpuColor, vm.gpuHistory.toList()),
                ChartSeries("Disk", DiskColor, vm.diskHistory.toList()),
            ),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("CPU", CpuColor)
            Legend("RAM", RamColor)
            Legend("GPU", GpuColor)
            Legend("Disk", DiskColor)
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BigPercent(value: Double, color: Color) {
    Text(
        formatPct1(value),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = color,
    )
}

@Composable
private fun CpuCard(snap: SystemSnapshot) {
    val cpu = snap.cpu
    SectionCard(title = "CPU", accent = CpuColor) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigPercent(cpu.overallPct, CpuColor)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                UsageBar(fraction = (cpu.overallPct / 100.0).toFloat(), color = CpuColor)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (cpu.cores.isNotEmpty()) {
            CoreBars(cpu.cores.map { it.usagePct.toFloat() }, CpuColor)
            Spacer(Modifier.height(10.dp))
        }
        StatRow("Cores", cpu.cores.size.toString())
        StatRow("Frequency", formatMhz(cpu.freqCurrentMhz))
        StatRow("Temperature", formatTemp(cpu.tempCelsius))
    }
}

@Composable
private fun CoreBars(cores: List<Float>, color: Color) {
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
                color = color.copy(alpha = 0.25f),
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
    SectionCard(title = "Memory", accent = RamColor) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigPercent(ram.usagePct, RamColor)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                UsageBar(fraction = (ram.usagePct / 100.0).toFloat(), color = RamColor)
            }
        }
        Spacer(Modifier.height(10.dp))
        StatRow("Used", "${formatBytes(ram.usedBytes)} / ${formatBytes(ram.totalBytes)}")
        StatRow("Available", formatBytes(ram.availableBytes))
        StatRow("Swap", "${formatBytes(ram.swapUsedBytes)} / ${formatBytes(ram.swapTotalBytes)} (${formatPct(ram.swapPct)})")
    }
}

@Composable
private fun GpuCard(dev: GpuDeviceStat) {
    val title = if (dev.isIgpu) "GPU (integrated)" else "GPU"
    SectionCard(title = title, accent = GpuColor) {
        Text(dev.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigPercent(dev.usagePct, GpuColor)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                UsageBar(fraction = (dev.usagePct / 100.0).toFloat(), color = GpuColor)
            }
        }
        Spacer(Modifier.height(10.dp))
        StatRow(
            "VRAM",
            "${formatBytes((dev.memUsedMb * 1024 * 1024).toLong())} / ${formatBytes((dev.memTotalMb * 1024 * 1024).toLong())} (${formatPct(dev.memPct)})",
        )
        StatRow("Temperature", formatTemp(dev.tempCelsius))
    }
}

@Composable
private fun DiskCard(snap: SystemSnapshot) {
    SectionCard(title = "Disks", accent = DiskColor) {
        snap.disk.drives.forEachIndexed { i, d ->
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
                Text(formatPct(d.usagePct), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            UsageBar(fraction = (d.usagePct / 100.0).toFloat(), color = DiskColor, barHeight = 8)
            Spacer(Modifier.height(6.dp))
            StatRow("Free", "${formatBytes(d.freeBytes)} of ${formatBytes(d.totalBytes)}")
            StatRow("I/O", "R ${formatRate(d.readBytesSec)} · W ${formatRate(d.writeBytesSec)}")
        }
    }
}
