package com.raavivi.sysmon.ui.processes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import kotlinx.coroutines.delay

@Composable
fun ProcessesScreen() {
    val vm = rememberContainerViewModel { ProcessesViewModel(it) }
    val rows = vm.rows()

    vm.toast?.let { msg ->
        LaunchedEffect(msg) { delay(2500); vm.toast = null }
    }

    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(title = "Processes")

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProcCategory.entries.forEach { cat ->
                FilterChip(
                    selected = vm.category == cat,
                    onClick = { vm.select(cat) },
                    label = { Text(cat.label) },
                )
            }
        }

        vm.toast?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        if (rows.isEmpty()) {
            Text(
                vm.error ?: "Waiting for process data…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { "${vm.category}-${it.pid}-${it.name}" }) { row ->
                    ProcessItem(row, onKill = { vm.requestKill(row.pid, row.name) })
                }
            }
        }
    }

    vm.pendingKill?.let { pk ->
        AlertDialog(
            onDismissRequest = vm::cancelKill,
            title = { Text("Kill process?") },
            text = {
                Text("End ${pk.name} (pid ${pk.pid})? This cannot be undone. Confirm within ${pk.ttlS}s.")
            },
            confirmButton = {
                TextButton(onClick = vm::confirmKill) {
                    Text("Kill", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = vm::cancelKill) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProcessItem(row: ProcRow, onKill: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.name, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                "pid ${row.pid} · ${row.sub}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            row.metric,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        IconButton(onClick = onKill) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Kill ${row.name}",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
