package com.raavivi.sysmon.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.SwitchRow
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import com.raavivi.sysmon.ui.theme.SysMon

/** Jellyfin link + stream-alert options (`/api/jellyfin/config`); admin-only. */
@Composable
fun JellyfinScreen(onBack: () -> Unit) {
    val vm = rememberContainerViewModel { JellyfinViewModel(it) }
    val side: Dp = if (SysMon.palette.isInstrument) 12.dp else 16.dp

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ScreenHeader(title = "Jellyfin", actions = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            })

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!vm.jfAvailable) {
                    SectionCard(title = "Not supported") {
                        Text(
                            "This server does not have Jellyfin settings. Update sys-mon.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    return@Column
                }

                Text(
                    "Detects who is streaming from your Jellyfin server and alerts the dashboard and phones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SectionCard(title = "Connection", flush = true) {
                    SwitchRow(
                        title = "Stream detection",
                        subtitle = "Poll Jellyfin for active streams",
                        checked = vm.jfEnabled,
                        onCheckedChange = { vm.jfEnabled = it },
                    )
                    Column(
                        Modifier.fillMaxWidth().padding(start = side, end = side, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = vm.jfUrl,
                            onValueChange = { vm.jfUrl = it },
                            label = { Text("URL") },
                            placeholder = { Text("http://127.0.0.1:8096") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = vm.jfKey,
                            onValueChange = { vm.jfKey = it },
                            label = { Text("API key") },
                            placeholder = { Text(if (vm.jfApiKeySet) "Saved - leave blank to keep" else "API key") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        NumberField("Poll every (seconds, 5-3600)", vm.jfPoll) { vm.jfPoll = it }
                        OutlinedButton(
                            onClick = vm::testJellyfin,
                            enabled = !vm.jfBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Test connection") }
                    }
                }

                SectionCard(title = "Alerts", flush = true) {
                    SwitchRow(title = "Dashboard alert", checked = vm.jfWeb, onCheckedChange = { vm.jfWeb = it })
                    SwitchRow(title = "Phone push", checked = vm.jfPush, onCheckedChange = { vm.jfPush = it })
                    SwitchRow(
                        title = "Block shutdown while streaming",
                        subtitle = "Ask again before restarting or shutting down while someone is watching",
                        checked = vm.jfGate,
                        onCheckedChange = { vm.jfGate = it },
                    )
                    Column(Modifier.fillMaxWidth().padding(start = side, end = side, bottom = 8.dp)) {
                        NumberField(
                            "Push refresh (seconds)",
                            vm.jfUpdate,
                            supporting = "0 = only when a stream starts or stops; otherwise 15-3600",
                        ) { vm.jfUpdate = it }
                    }
                }

                SectionCard(title = "Alert details", flush = true) {
                    SwitchRow(title = "User name", checked = vm.jfUser, onCheckedChange = { vm.jfUser = it })
                    SwitchRow(title = "Title", checked = vm.jfTitle, onCheckedChange = { vm.jfTitle = it })
                    SwitchRow(title = "Playback position", checked = vm.jfPosition, onCheckedChange = { vm.jfPosition = it })
                    SwitchRow(title = "Device / client", checked = vm.jfDevice, onCheckedChange = { vm.jfDevice = it })
                }
            }
        }

        if (vm.jfAvailable) {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.jfStatus?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("Failed")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = vm::saveJellyfin,
                        enabled = vm.jfLoaded && !vm.jfBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    supporting: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
