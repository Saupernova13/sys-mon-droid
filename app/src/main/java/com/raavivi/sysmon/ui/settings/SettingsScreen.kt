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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import android.content.Intent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import com.raavivi.sysmon.ui.theme.SysMon
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

        if (vm.features?.settingsUnreadable == true) {
            Text(
                "The server's settings file was unreadable and has been archived (settings.json.corrupt-<timestamp>). " +
                    "The flags shown may be defaults, not your saved configuration.",
                color = SysMon.palette.sigWarn,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

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

            if (vm.jfAvailable) JellyfinCard(vm)

            LogsCard(vm)

            Text(
                "Flags apply live where the host supports it; the ollama proxy " +
                    "only unbinds its port after a server restart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Jellyfin link + stream-alert options (`/api/jellyfin/config`); admin-only like the rest of this screen. */
@Composable
private fun JellyfinCard(vm: SettingsViewModel) {
    SectionCard(title = "Jellyfin") {
        Text(
            "Detects who is streaming from your Jellyfin server and alerts the dashboard and the phone app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = vm.jfUrl,
            onValueChange = { vm.jfUrl = it },
            label = { Text("URL") },
            placeholder = { Text("http://127.0.0.1:8096") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = vm.jfKey,
            onValueChange = { vm.jfKey = it },
            label = { Text("API key") },
            placeholder = { Text(if (vm.jfApiKeySet) "Saved - leave blank to keep" else "API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        JfSwitch("Enabled", null, vm.jfEnabled) { vm.jfEnabled = it }
        JfNumber("Poll every (seconds, 5-3600)", vm.jfPoll) { vm.jfPoll = it }

        Text("Alerts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        JfSwitch("Web alert", null, vm.jfWeb) { vm.jfWeb = it }
        JfSwitch("Phone push", null, vm.jfPush) { vm.jfPush = it }
        JfSwitch("Include user name", null, vm.jfUser) { vm.jfUser = it }
        JfSwitch("Include title", null, vm.jfTitle) { vm.jfTitle = it }
        JfSwitch("Include playback position", null, vm.jfPosition) { vm.jfPosition = it }
        JfSwitch("Include device / client", null, vm.jfDevice) { vm.jfDevice = it }
        JfSwitch(
            "Block shutdown / restart while streaming",
            "Ask for an extra confirmation before shutting down or restarting while someone is watching.",
            vm.jfGate,
        ) { vm.jfGate = it }
        JfNumber("Push refresh (seconds, 0 = never)", vm.jfUpdate) { vm.jfUpdate = it }
        Text(
            "0 = only push when a stream starts or stops.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::saveJellyfin, enabled = vm.jfLoaded && !vm.jfBusy) { Text("Save Jellyfin") }
            OutlinedButton(onClick = vm::testJellyfin, enabled = !vm.jfBusy) { Text("Test connection") }
        }
        vm.jfStatus?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Failed")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** On-device logs: on/off switch, folder, share (zip + share sheet) and clear. */
@Composable
private fun LogsCard(vm: SettingsViewModel) {
    val context = LocalContext.current
    SectionCard(title = "Logs") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Write logs", style = MaterialTheme.typography.bodyLarge)
                if (!vm.loggingEnabled) {
                    Text(
                        "Logging is off",
                        style = MaterialTheme.typography.bodySmall,
                        color = SysMon.palette.sigWarn,
                    )
                }
            }
            Switch(checked = vm.loggingEnabled, onCheckedChange = vm::setLogging)
        }
        Text(
            "Folder: ${vm.logsLocation}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Kept for 24 hours, then deleted. No tokens, bodies or coordinates are logged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    vm.shareLogs { file ->
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Share logs"))
                    }
                },
                enabled = !vm.logsBusy,
            ) { Text("Share logs") }
            OutlinedButton(onClick = vm::clearLogs, enabled = !vm.logsBusy) { Text("Clear logs") }
        }
    }
}

@Composable
private fun JfSwitch(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun JfNumber(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
