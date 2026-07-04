package com.raavivi.sysmon.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import kotlinx.coroutines.delay

/** Admin-only: flips the server's widget feature flags via `POST /api/settings`. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = rememberContainerViewModel { SettingsViewModel(it) }

    vm.message?.let { msg -> LaunchedEffect(msg) { delay(4000); vm.message = null } }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = "Server settings", actions = {
            IconButton(onClick = vm::refresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        })

        (vm.message ?: vm.error)?.let {
            Text(
                it,
                color = if (vm.error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(title = "Widgets") {
                val rows = vm.rows()
                if (rows.isEmpty()) {
                    Text(
                        "Feature flags unavailable",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                rows.forEach { row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.label, style = MaterialTheme.typography.bodyLarge)
                            row.hint?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = row.enabled,
                            onCheckedChange = { vm.toggle(row.key, it) },
                            enabled = !vm.busy,
                        )
                    }
                }
            }

            Text(
                "Flags apply live where the host supports it; the ollama proxy " +
                    "only unbinds its port after a server restart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
