package com.raavivi.sysmon.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.SettingsRow
import com.raavivi.sysmon.ui.common.SwitchRow
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import com.raavivi.sysmon.ui.theme.SysMon

/** Admin-only: flips the server's widget feature flags via `POST /api/settings`. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = rememberContainerViewModel { SettingsViewModel(it) }
    val snackbar = remember { SnackbarHostState() }

    vm.message?.let { msg ->
        LaunchedEffect(msg) {
            snackbar.showSnackbar(msg)
            vm.message = null
        }
    }
    vm.error?.let { err ->
        LaunchedEffect(err) {
            snackbar.showSnackbar("Failed: $err")
            vm.error = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenHeader(title = "Server features", actions = {
                IconButton(onClick = vm::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            })

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (vm.features?.settingsUnreadable == true) {
                    SectionCard(title = "Settings file was reset", accent = SysMon.palette.sigWarn) {
                        Text(
                            "The server's settings file was unreadable and has been archived (settings.json.corrupt-<timestamp>). " +
                                "The flags shown may be defaults, not your saved configuration.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                SectionCard(title = "Modules", flush = true) {
                    val rows = vm.rows()
                    if (rows.isEmpty()) {
                        SettingsRow(title = "Feature flags unavailable", subtitle = "Tap refresh to try again")
                    }
                    rows.forEach { row ->
                        SwitchRow(
                            icon = flagIcon(row.key),
                            title = row.label,
                            subtitle = row.hint,
                            checked = row.enabled,
                            enabled = !vm.busy,
                            onCheckedChange = { vm.toggle(row.key, it) },
                        )
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
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

private fun flagIcon(key: String): ImageVector? = when (key) {
    "model_log" -> Icons.Filled.Psychology
    "whatsapp" -> Icons.AutoMirrored.Filled.Chat
    "ollama_proxy" -> Icons.Filled.Hub
    "godot" -> Icons.Filled.SportsEsports
    "power" -> Icons.Filled.Power
    "crystaldisk" -> Icons.Filled.Storage
    "findme" -> Icons.Filled.MyLocation
    else -> null
}
