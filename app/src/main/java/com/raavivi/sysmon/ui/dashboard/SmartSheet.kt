package com.raavivi.sysmon.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raavivi.sysmon.core.model.SmartDisk
import com.raavivi.sysmon.ui.common.Metric
import com.raavivi.sysmon.ui.common.Severity
import com.raavivi.sysmon.ui.common.StatRow
import com.raavivi.sysmon.ui.common.band
import com.raavivi.sysmon.ui.common.color
import com.raavivi.sysmon.ui.common.diskTempMetric
import com.raavivi.sysmon.ui.common.healthState
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import com.raavivi.sysmon.ui.theme.SysMon
import java.util.Locale

/** S.M.A.R.T. attribute names by id; an unknown id shows as "Attribute XX". */
private val ATTRIBUTE_NAMES = mapOf(
    "01" to "Read Error Rate", "02" to "Throughput Performance", "03" to "Spin-Up Time",
    "04" to "Start/Stop Count", "05" to "Reallocated Sectors Count", "07" to "Seek Error Rate",
    "08" to "Seek Time Performance", "09" to "Power-On Hours", "0A" to "Spin Retry Count",
    "0B" to "Recalibration Retries", "0C" to "Power Cycle Count", "AA" to "Available Reserved Space",
    "AB" to "Program Fail Count", "AC" to "Erase Fail Count", "AD" to "Wear Leveling Count",
    "AE" to "Unexpected Power Loss", "AF" to "Power Loss Protection Failure", "B0" to "Erase Fail Count",
    "B1" to "Wear Range Delta", "B2" to "Used Reserved Block Count",
    "B3" to "Used Reserved Block Count (total)", "B4" to "Unused Reserved Block Count",
    "B5" to "Program Fail Count (total)", "B6" to "Erase Fail Count", "B7" to "Runtime Bad Block",
    "B8" to "End-to-End Error", "BB" to "Reported Uncorrectable Errors", "BC" to "Command Timeout",
    "BD" to "High Fly Writes", "BE" to "Airflow Temperature", "BF" to "G-Sense Error Rate",
    "C0" to "Power-off Retract Count", "C1" to "Load/Unload Cycle Count", "C2" to "Temperature",
    "C3" to "Hardware ECC Recovered", "C4" to "Reallocation Event Count",
    "C5" to "Current Pending Sector Count", "C6" to "Uncorrectable Sector Count",
    "C7" to "UltraDMA CRC Error Count", "C8" to "Write Error Rate", "C9" to "Soft Read Error Rate",
    "CA" to "Data Address Mark Errors", "CB" to "Run Out Cancel", "CC" to "Soft ECC Correction",
    "CD" to "Thermal Asperity Rate", "DF" to "Load/Unload Retry Count", "E1" to "Load/Unload Cycle Count",
    "E7" to "SSD Life Left", "E8" to "Available Reserved Space", "E9" to "Media Wearout Indicator",
    "F0" to "Head Flying Hours", "F1" to "Total Host Writes", "F2" to "Total Host Reads",
    "F5" to "Total NAND Writes",
)

internal fun attributeName(id: String): String = ATTRIBUTE_NAMES[id] ?: "Attribute $id"

/** The counters that decide CrystalDiskInfo's verdict; any above zero is called out. */
private val SECTOR_COUNTERS = listOf(
    "ReallocatedSectorsCount" to "reallocated",
    "CurrentPendingSectorCount" to "pending",
    "UncorrectableSectorCount" to "uncorrectable",
    "ReallocationEventCount" to "realloc events",
)

/** Named readings promoted out of the raw table: key, label, unit suffix. */
private val HEADLINE_VALUES = listOf(
    Triple("PowerOnHours", "power on", " h"),
    Triple("PowerOnCount", "power cycles", ""),
    Triple("Life", "life left", "%"),
    Triple("HostReads", "host reads", " GB"),
    Triple("HostWrites", "host writes", " GB"),
    Triple("NandWrites", "nand writes", " GB"),
)

private val HEALTH_LABEL = mapOf("good" to "Good", "caution" to "Caution", "bad" to "Bad", "unknown" to "Unknown")

internal fun healthLabel(health: String?): String? = health?.let { HEALTH_LABEL[it] }

/** A spinning disk reports its speed ("7200 RPM"); anything else is treated as an SSD. */
internal fun SmartDisk.isRotational(): Boolean = rotation.contains("rpm", ignoreCase = true)

@Composable
internal fun SmartDisk.tempState(): Severity =
    band(diskTempMetric(null, isRotational()), temperatureC)

private fun groupInt(n: Double): String = String.format(Locale.US, "%,d", n.toLong())

/**
 * Bottom sheet port of the web S.M.A.R.T. modal. [diskId] / [volumeKey] pick the
 * disk shown first; every disk the source reports is reachable from the chips.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SmartSheet(diskId: String?, volumeKey: String?, onDismiss: () -> Unit) {
    val vm = rememberContainerViewModel { SmartViewModel(it, diskId, volumeKey) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val p = SysMon.palette
    val response = vm.response
    val disks = response?.disks.orEmpty()
    val selected = disks.firstOrNull { it.id == vm.selectedId } ?: disks.firstOrNull()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Disk health — S.M.A.R.T.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Close") }
            }

            when {
                vm.off -> Muted("S.M.A.R.T. is turned off on the server")
                vm.loading -> Muted("Reading disks…")
                vm.error != null && disks.isEmpty() -> Text(
                    vm.error.orEmpty(),
                    color = p.sigCrit,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            response?.notes?.forEach {
                Text(it, color = p.sigWarn, style = MaterialTheme.typography.bodySmall)
            }
            // A probe error with disks present means the last good reading is shown.
            if (response != null && response.error.isNotBlank() && disks.isNotEmpty()) {
                Text(
                    "${response.error} — showing the last good reading",
                    color = p.sigWarn,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (response != null && disks.isEmpty() && !vm.loading) {
                Muted(
                    if (vm.sourceName == "smartctl") {
                        "No disks reported. smartctl reads the drives live; check that it is " +
                            "installed and that sys-mon is allowed to run it (SYS_MON_SMARTCTL), " +
                            "then use Rescan."
                    } else {
                        "No disks reported. CrystalDiskInfo only reports a disk once it has been " +
                            "scanned today; sys-mon scans once a day on its own, or use Rescan."
                    },
                )
            }

            if (disks.size > 1) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    disks.forEach { d ->
                        FilterChip(
                            selected = d.id == selected?.id,
                            onClick = { vm.select(d.id) },
                            label = {
                                Text(
                                    "${d.model.ifBlank { d.id }} · " +
                                        (d.letters.joinToString(" ").ifEmpty { "no volume" }),
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }

            selected?.let { DiskDetail(it) }

            HorizontalDivider(color = p.hairline)
            Footer(vm, selected?.scannedAt)
        }
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Footer(vm: SmartViewModel, scannedAt: String?) {
    val p = SysMon.palette
    Text(
        "Read via ${vm.sourceName}" + (scannedAt?.let { " · scanned $it" } ?: ""),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (vm.error != null && vm.response != null) {
        Text(vm.error.orEmpty(), color = p.sigCrit, style = MaterialTheme.typography.bodySmall)
    }
    if (vm.isAdmin && !vm.off) {
        OutlinedButton(onClick = vm::rescan, enabled = !vm.rescanning && !vm.loading) {
            Text(if (vm.rescanning) "Rescanning…" else "Rescan")
        }
        Text(
            "Ask ${vm.sourceName} for a fresh reading." +
                if (vm.isWindows) " This launches CrystalDiskInfo and spins the disks up." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = SysMon.palette.quietInk,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun DiskDetail(d: SmartDisk) {
    val p = SysMon.palette
    val health = healthState(d.health)
    val tempState = d.tempState()
    val temp = d.temperatureC?.let { String.format(Locale.US, "%.0f °C", it) } ?: "—"

    // Verdict
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.model.ifBlank { d.id }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                d.letters.joinToString(" ").ifEmpty { "no mounted volume" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                healthLabel(d.health) ?: "Unknown",
                color = health.color(p.sigOk),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                temp,
                color = tempState.color(MaterialTheme.colorScheme.onSurface),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    // Identity
    SectionLabel("Identity")
    StatRow("Model", d.model.ifBlank { "—" })
    StatRow("Serial", d.serial.ifBlank { "—" })
    StatRow("Firmware", d.firmware.ifBlank { "—" })
    StatRow("Interface", d.interfaceName.ifBlank { "—" })
    StatRow("Rotation", d.rotation.ifBlank { "—" })
    StatRow("Capacity", d.size.ifBlank { "—" })
    StatRow("Volumes", d.letters.joinToString(" ").ifEmpty { "—" })
    StatRow("Scanned", d.scannedAt ?: "—")

    // Usage counters
    val usage = buildList {
        for ((key, label, unit) in HEADLINE_VALUES) {
            val n = d.values.number(key)
                ?: when (key) {
                    "PowerOnHours" -> d.powerOnHours?.toDouble()
                    "PowerOnCount" -> d.powerOnCount?.toDouble()
                    "Life" -> d.lifePct
                    else -> null
                }
            if (n != null) add(label to groupInt(n) + unit)
        }
    }
    if (usage.isNotEmpty()) {
        SectionLabel("Usage")
        usage.forEach { (k, v) -> StatRow(k, v) }
    }

    // Sector counters: any non-zero value is why a disk reads Caution
    val counters = SECTOR_COUNTERS.mapNotNull { (key, label) ->
        d.values.number(key)?.let { label to it }
    }
    if (counters.isNotEmpty()) {
        SectionLabel("Sector health")
        counters.forEach { (label, v) ->
            StatRow(
                label,
                groupInt(v),
                valueColor = if (v > 0) Severity.Caution.color(p.sigWarn) else Color.Unspecified,
            )
        }
    }

    AttributeSection(d)
    OtherReadings(d)
}

@Composable
private fun AttributeSection(d: SmartDisk) {
    val p = SysMon.palette
    var showAll by remember(d.id) { mutableStateOf(false) }
    val ids = d.attributes?.keys?.sorted().orEmpty()
    SectionLabel("S.M.A.R.T. attributes")
    if (ids.isEmpty()) {
        Muted("No attributes reported.")
        return
    }
    // failing == null means thresholds could not be read at all; that must never
    // look like "checked, nothing failing".
    if (d.failing == null) {
        Text(
            "Thresholds unavailable for this disk — failing attributes cannot be detected. " +
                "Readings only, not a clean bill of health.",
            color = p.sigWarn,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val failing = d.failing.orEmpty().toSet()
    ids.filter { it in failing }.forEach { AttributeRow(d, it, true) }
    val rest = ids.filter { it !in failing }
    if (rest.isNotEmpty()) {
        TextButton(onClick = { showAll = !showAll }) {
            Text(if (showAll) "Hide other attributes" else "Show other attributes (${rest.size})")
        }
        if (showAll) {
            Row(Modifier.fillMaxWidth()) {
                Text("ID", Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall, color = p.quietInk)
                Text("Attribute", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = p.quietInk)
                Text("Cur", Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall, color = p.quietInk)
                Text("Thr", Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall, color = p.quietInk)
            }
            rest.forEach { AttributeRow(d, it, false) }
        }
    }
}

@Composable
private fun AttributeRow(d: SmartDisk, id: String, failing: Boolean) {
    val p = SysMon.palette
    val value = d.attributes.text(id) ?: "—"
    val raw = d.thresholds.text(id)
    // A zero threshold is one the manufacturer never trips.
    val threshold = when (raw) {
        null -> "—"
        "0" -> "none"
        else -> raw
    }
    val ink = if (failing) p.sigCrit else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(id, Modifier.width(36.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = ink)
        Column(Modifier.weight(1f)) {
            Text(attributeName(id), style = MaterialTheme.typography.bodySmall, color = ink)
            if (failing) {
                Text("FAILING", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = p.sigCrit)
            }
        }
        Text(value, Modifier.width(48.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = ink)
        Text(threshold, Modifier.width(48.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = ink)
    }
}

/** Readings the source returned that no section above already shows. */
@Composable
private fun OtherReadings(d: SmartDisk) {
    val shown = setOf("Date", "HealthStatus", "Temperature") +
        HEADLINE_VALUES.map { it.first } + SECTOR_COUNTERS.map { it.first }
    val rest = d.values?.keys.orEmpty().filter { it !in shown }
    if (rest.isEmpty()) return
    SectionLabel("Other readings")
    rest.forEach { StatRow(it, d.values.text(it) ?: "—") }
    Spacer(Modifier.height(4.dp))
}
