package com.raavivi.sysmon.ui.power

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.PowerSchedule
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.theme.PowerColor
import kotlinx.coroutines.delay

/**
 * Per-plug on/off windows: switch the plug on at one time and off at another, on
 * the chosen weekdays. Windows may wrap past midnight (off earlier than on).
 *
 * Edits are a local draft until Save — the endpoint replaces a plug's whole set
 * in one call, so there is no meaningful per-row save.
 */
@Composable
fun PowerSchedulesTab(
    vm: PowerSchedulesViewModel,
    plug: String,
    plugName: String,
    isAdmin: Boolean,
) {
    vm.message?.let { LaunchedEffect(it) { delay(3000); vm.message = null } }

    if (plug.isEmpty()) {
        SectionCard(title = "Schedules", accent = PowerColor) {
            Text(
                "Pick a specific plug above to set its on/off schedule.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    SectionCard(
        title = "Schedules",
        accent = PowerColor,
        trailing = {
            Text(
                plugName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        (vm.error ?: vm.message)?.let {
            Text(
                it,
                color = if (vm.error != null) MaterialTheme.colorScheme.error else PowerColor,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (vm.rows.isEmpty()) {
            Text(
                if (vm.loading) "Loading…"
                else if (isAdmin) "No windows yet — add one below."
                else "No schedule set.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        vm.rows.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(
                    Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                )
            }
            ScheduleRow(
                row = row,
                enabled = isAdmin,
                onChange = { updated -> vm.updateRow(index) { updated } },
                onToggleDay = { day -> vm.toggleDay(index, day) },
                onRemove = { vm.removeRow(index) },
            )
        }

        if (!isAdmin) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Signed in as viewer — schedules are read-only.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::addRow, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add window")
            }
            Button(
                onClick = vm::save,
                modifier = Modifier.weight(1f),
                enabled = !vm.saving && vm.dirty,
            ) {
                Text(if (vm.saving) "Saving…" else "Save")
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    row: PowerSchedule,
    enabled: Boolean,
    onChange: (PowerSchedule) -> Unit,
    onToggleDay: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = row.label,
                onValueChange = { onChange(row.copy(label = it)) },
                label = { Text("Label") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = row.enabled,
                onCheckedChange = { onChange(row.copy(enabled = it)) },
                enabled = enabled,
            )
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(Icons.Filled.Close, contentDescription = "Remove window")
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeField(
                label = "On",
                value = row.on,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onPicked = { onChange(row.copy(on = it)) },
            )
            TimeField(
                label = "Off",
                value = row.off,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onPicked = { onChange(row.copy(off = it)) },
            )
        }

        if (wrapsMidnight(row.on, row.off)) {
            Text(
                "Runs overnight into the next day.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            PowerSchedule.DAY_LABELS.forEachIndexed { day, label ->
                FilterChip(
                    selected = day in row.days,
                    onClick = { onToggleDay(day) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    label = {
                        Text(
                            label.take(1),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }
        }
        if (row.days.isEmpty()) {
            Text(
                "No days selected — this window never fires.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A read-only field that opens the Material time picker — typing `HH:mm` on a
 *  phone keyboard is error-prone, and the backend rejects anything malformed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(
    label: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier,
    onPicked: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { showPicker = true },
        enabled = enabled,
        modifier = modifier,
    ) {
        Text("$label  $value")
    }

    if (showPicker) {
        val (h, m) = parseHhMm(value)
        val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onPicked("%02d:%02d".format(state.hour, state.minute))
                    showPicker = false
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
            title = { Text("$label time") },
            text = { TimePicker(state = state) },
        )
    }
}

private fun parseHhMm(value: String): Pair<Int, Int> {
    val parts = value.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
    val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    return h to m
}

private fun wrapsMidnight(on: String, off: String): Boolean {
    val (oh, om) = parseHhMm(on)
    val (fh, fm) = parseHhMm(off)
    return (fh * 60 + fm) < (oh * 60 + om)
}
