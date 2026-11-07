package com.raavivi.sysmon.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raavivi.sysmon.LocalAppContainer
import com.raavivi.sysmon.core.model.HostInfo
import com.raavivi.sysmon.core.model.DriveStat
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
import com.raavivi.sysmon.ui.common.diskTempMetric
import com.raavivi.sysmon.ui.common.healthState
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
import java.util.Locale

@Composable
fun DashboardScreen(onOpenPower: () -> Unit = {}) {
    val vm = rememberContainerViewModel { DashboardViewModel(it) }
    var menuOpen by remember { mutableStateOf(false) }
    val snap = vm.snapshot
    val container = LocalAppContainer.current
    val features by container.session.features.collectAsStateWithLifecycle()
    val smartEnabled = features?.crystaldisk == true
    var smartTarget by remember { mutableStateOf<DriveStat?>(null) }

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
                item { DiskCard(snap, smartEnabled) { smartTarget = it } }
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

    smartTarget?.let { d ->
        SmartSheet(
            diskId = d.smartDiskId,
            volumeKey = d.mountpoint.ifBlank { d.device },
            onDismiss = { smartTarget = null },
        )
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
    value: Double?,
    state: Severity,
    identity: Color,
) {
    val fraction = ((value ?: 0.0) / 100.0).toFloat()
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
        if (cpu.tempCelsius == null) {
            val host by LocalAppContainer.current.session.host.collectAsStateWithLifecycle()
            Text(
                noTempHint(host),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Why there is no CPU temperature, by host OS (mirrors the web's NO_TEMP_HINT). */
internal fun noTempHint(host: HostInfo?): String = when (host?.os) {
    null, "", "windows" -> "CPU temperature unavailable on Windows without LibreHardwareMonitor"
    "linux" -> "No hwmon CPU temperature sensor found (is the coretemp or k10temp kernel module loaded?)"
    else -> "CPU temperature unavailable on this machine"
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
            "${formatBytes(mbToBytes(dev.memUsedMb))} / ${formatBytes(mbToBytes(dev.memTotalMb))} (${formatPct(dev.memPct)})",
        )
        StatRow(
            "Temperature",
            formatTemp(dev.tempCelsius),
            valueColor = tempInk(temp, dev.tempCelsius),
        )
    }
}


private fun mbToBytes(mb: Double?): Long? = mb?.let { (it * 1024 * 1024).toLong() }

/** "C:\" or "/home", plus " (label)" once the volume has one (render/disk.js driveTitle). */
internal fun driveTitle(d: DriveStat): String {
    val name = d.mountpoint.ifBlank { d.device }
    return if (d.label.isNotBlank()) "$name (${d.label})" else name
}

/** "NVMe SSD" or whichever half the device reported. */
internal fun driveType(d: DriveStat): String =
    listOf(d.busType, d.mediaType).filter { it.isNotBlank() }.joinToString(" ")

/** "NVMe SSD · Model" with blanks omitted. */
internal fun driveHardware(d: DriveStat): String =
    listOf(driveType(d), d.model).filter { it.isNotBlank() }.joinToString(" · ")

/** The S.M.A.R.T. fact text, or null when there is no reading at all. */
internal fun smartFactText(d: DriveStat): String? {
    if (d.smartHealth == null && d.smartTempC == null) return null
    val temp = d.smartTempC?.let { String.format(Locale.US, "%.0f°C", it) } ?: "—"
    val health = healthLabel(d.smartHealth) ?: "—"
    return "$temp · $health"
}

/** Worse of the source's own verdict and the temperature band; null with no reading. */
@Composable
private fun smartState(d: DriveStat): Severity? {
    if (d.smartHealth == null && d.smartTempC == null) return null
    return worst(
        healthState(d.smartHealth),
        band(diskTempMetric(d.mediaType, d.smartRotational), d.smartTempC),
    )
}

@Composable
private fun DiskCard(snap: SystemSnapshot, smartEnabled: Boolean, onOpenSmart: (DriveStat) -> Unit) {
    val p = SysMon.palette
    val drives = snap.disk.drives
    val capacity = drives.map { band(Metric.DiskUsed, it.usagePct) }
    val smart = drives.map { smartState(it) }
    val cardState = worst(*(capacity + smart.filterNotNull()).toTypedArray())
    SectionCard(title = "Disks", accent = p.idDisk, state = cardState) {
        drives.forEachIndexed { i, d ->
            val state = capacity[i]
            val canOpen = smartEnabled && d.smartDiskId != null
            if (i > 0) Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (canOpen) Modifier.clickable { onOpenSmart(d) } else Modifier),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        driveTitle(d),
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
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
                driveHardware(d).takeIf { it.isNotEmpty() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = p.quietInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                StatRow("Used", "${formatBytes(d.usedBytes)} / ${formatBytes(d.totalBytes)}")
                StatRow("I/O", "R ${formatRate(d.readBytesSec)} · W ${formatRate(d.writeBytesSec)}")
                smartFactText(d)?.let { text ->
                    val s = smart[i]
                    StatRow(
                        "S.M.A.R.T.",
                        text,
                        valueColor = if (s != null && s != Severity.Nominal) s.color(p.sigOk) else Color.Unspecified,
                    )
                }
            }
        }
    }
}
