package com.raavivi.sysmon.ui.modellog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.ModelLogRow
import com.raavivi.sysmon.ui.common.ErrorBox
import com.raavivi.sysmon.ui.common.LoadingBox
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.StatRow
import com.raavivi.sysmon.ui.common.formatDuration
import com.raavivi.sysmon.ui.common.formatTime
import com.raavivi.sysmon.ui.common.relativeTime
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import kotlinx.coroutines.delay

@Composable
fun ModelLogScreen() {
    val vm = rememberContainerViewModel { ModelLogViewModel(it) }

    vm.message?.let { msg -> LaunchedEffect(msg) { delay(2500); vm.message = null } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Model Log", actions = {
            ProxyDot(vm.proxyRunning)
            Spacer(Modifier.size(8.dp))
            IconButton(onClick = vm::fetch) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            IconButton(onClick = vm::clear) { Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear log") }
        })

        vm.message?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        Box(Modifier.fillMaxSize()) {
            when {
                vm.loading && vm.items.isEmpty() -> LoadingBox()
                vm.error != null && vm.items.isEmpty() -> ErrorBox(vm.error!!, onRetry = vm::fetch)
                vm.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No LLM requests recorded yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    items(vm.items, key = { it.id }) { row ->
                        LogRow(row, onClick = { vm.detail = row })
                    }
                }
            }
        }
    }

    vm.detail?.let { row -> LogDetailDialog(row, onDismiss = { vm.detail = null }) }
}

@Composable
private fun ProxyDot(running: Boolean?) {
    val color = when (running) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.onSurfaceVariant
        null -> MaterialTheme.colorScheme.tertiary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            if (running == true) "llama up" else "idle",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogRow(row: ModelLogRow, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTime(row.ts),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            ServiceTag(row.service)
            if (row.coldStart) {
                Spacer(Modifier.size(6.dp))
                ColdStartBadge()
            }
            Spacer(Modifier.weight(1f))
            row.status?.let {
                Text(
                    it.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (it in 200..299) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.size(2.dp))
        Text(
            row.model ?: "(model unknown)",
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Text(
            row.message.replace('\n', ' '),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        Text(
            "${row.callerProc ?: row.callerIp ?: "?"} · ${formatDuration(row.durationMs)} · ${relativeTime(row.ts)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ServiceTag(service: String) {
    val color = if (service.contains("ollama", true)) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(service, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun ColdStartBadge() {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            "COLD START",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LogDetailDialog(row: ModelLogRow, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(row.model ?: row.service) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                StatRow("Time", formatTime(row.ts))
                StatRow("Service", row.service)
                StatRow("Endpoint", "${row.method ?: ""} ${row.endpoint ?: ""}".trim())
                StatRow("Caller", row.callerProc ?: row.callerIp ?: "?")
                StatRow("Status", row.status?.toString() ?: "—")
                StatRow("Duration", formatDuration(row.durationMs))
                StatRow("Cold start", if (row.coldStart) "yes" else "no")
                StatRow("Streamed", if (row.stream) "yes" else "no")
                Spacer(Modifier.size(8.dp))
                Text("Message", fontWeight = FontWeight.SemiBold)
                Text(
                    row.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
    )
}
