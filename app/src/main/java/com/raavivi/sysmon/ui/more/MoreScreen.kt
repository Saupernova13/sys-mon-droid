package com.raavivi.sysmon.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raavivi.sysmon.BuildConfig
import com.raavivi.sysmon.LocalAppContainer
import com.raavivi.sysmon.core.auth.SessionManager
import com.raavivi.sysmon.core.log.AppLog
import com.raavivi.sysmon.core.update.UpdateCheck
import com.raavivi.sysmon.ui.common.ActionTileGrid
import com.raavivi.sysmon.ui.common.Chevron
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.SettingsRow
import com.raavivi.sysmon.ui.common.Tile
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import com.raavivi.sysmon.ui.theme.SysMon
import com.raavivi.sysmon.ui.theme.LocalThemeStyle
import com.raavivi.sysmon.ui.theme.ThemeStyle
import kotlinx.coroutines.launch

@Composable
fun MoreScreen(
    onOpenTerminal: () -> Unit = {},
    onOpenScreen: () -> Unit = {},
    onOpenModelLog: () -> Unit = {},
    onOpenFindMe: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenJellyfin: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenHealth: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
    onOpenSchedules: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenProcesses: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
) {
    val vm = rememberContainerViewModel { MoreViewModel(it) }
    val container = LocalAppContainer.current
    val palette = SysMon.palette
    val role by container.session.role.collectAsStateWithLifecycle()
    val features by container.session.features.collectAsStateWithLifecycle()
    val host by container.session.host.collectAsStateWithLifecycle()
    val updateUi by container.updates.ui.collectAsStateWithLifecycle()
    val pushOn by container.settings.pushEnabled.collectAsStateWithLifecycle(initialValue = false)
    val loggingOn by container.settings.loggingEnabled.collectAsStateWithLifecycle(initialValue = AppLog.isEnabled())
    val isAdmin = role == SessionManager.ROLE_ADMIN
    val minimal = LocalThemeStyle.current == ThemeStyle.Minimal
    val available = updateUi.check as? UpdateCheck.Available
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val snackbar = remember { SnackbarHostState() }

    vm.message?.let { msg ->
        LaunchedEffect(msg) {
            snackbar.showSnackbar(msg)
            vm.message = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenHeader(title = "More")

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AccountHeader(username = vm.username, role = role, serverUrl = vm.serverUrl)

                if (available != null) {
                    SectionCard(title = "Update available", flush = true) {
                        SettingsRow(
                            icon = Icons.Filled.SystemUpdate,
                            title = "Version ${available.release.versionName} is ready",
                            subtitle = "Tap to download and install",
                            onClick = onOpenUpdate,
                            trailing = { Chevron() },
                        )
                    }
                }

                SectionCard(title = "Tools") {
                    val tiles = buildList {
                        if (isAdmin) {
                            add(Tile("Terminal", Icons.Filled.Terminal, palette.idCpu, onOpenTerminal))
                            add(Tile("Screen share", Icons.AutoMirrored.Filled.ScreenShare, palette.idGpu, onOpenScreen))
                            if (features?.findme == true && !minimal) {
                                add(Tile("Find Me", Icons.Filled.MyLocation, palette.idMem, onOpenFindMe))
                            }
                            if (features?.modelLog == true) {
                                add(Tile("Model log", Icons.Filled.Psychology, palette.idDisk, onOpenModelLog))
                            }
                        }
                        if (minimal) {
                            // Stats, Procs and Files are not tabs in Minimal, so they live here.
                            add(Tile("Stats", Icons.Filled.Dashboard, palette.idCpu, onOpenStats))
                            add(Tile("Procs", Icons.Filled.Memory, palette.idMem, onOpenProcesses))
                            add(Tile("Files", Icons.Filled.Folder, palette.idDisk, onOpenFiles))
                        } else {
                            add(Tile("Health", Icons.Filled.MonitorHeart, palette.sigOk, onOpenHealth))
                        }
                        add(Tile("Schedules", Icons.Filled.Schedule, palette.idPwr, onOpenSchedules))
                    }
                    ActionTileGrid(tiles)
                }

                // Host actions mutate the host, so the read-only viewer role never sees them.
                if (isAdmin) {
                    SectionCard(title = "Host", flush = true) {
                        if (features?.remoteControl == true) {
                            SettingsRow(
                                icon = Icons.Filled.SmartToy,
                                title = "Launch remote control",
                                subtitle = "Start remote control on the host",
                                onClick = vm::remoteControl,
                            )
                        }
                        SettingsRow(
                            icon = Icons.Filled.Backup,
                            title = "Back up history",
                            subtitle = "Write a history backup on the host",
                            onClick = vm::backup,
                        )
                        SettingsRow(
                            icon = Icons.Filled.RestartAlt,
                            title = "Restart host",
                            tint = palette.sigWarn,
                            tintTitle = true,
                            onClick = {
                                confirm = Confirm("Restart host?", powerPrompt("Restart", host)) { vm.restart() }
                            },
                        )
                        SettingsRow(
                            icon = Icons.Filled.PowerSettingsNew,
                            title = "Shut down host",
                            tint = palette.sigCrit,
                            tintTitle = true,
                            onClick = {
                                confirm = Confirm("Shut down host?", powerPrompt("Shut down", host)) { vm.shutdown() }
                            },
                        )
                    }
                }

                SectionCard(title = "This phone", flush = true) {
                    SettingsRow(
                        icon = Icons.Filled.Notifications,
                        title = "Notifications",
                        subtitle = when {
                            features?.push != true -> "Unavailable - server has no push key"
                            pushOn -> "On for this phone"
                            else -> "Off for this phone"
                        },
                        onClick = onOpenNotifications,
                        trailing = { Chevron() },
                    )
                    ThemeRow()
                    SettingsRow(
                        icon = Icons.Filled.Description,
                        title = "Logs",
                        subtitle = if (loggingOn) "On · kept for 24 hours" else "Off",
                        onClick = onOpenLogs,
                        trailing = { Chevron() },
                    )
                    SettingsRow(
                        icon = Icons.Filled.SystemUpdate,
                        title = "App updates",
                        subtitle = if (available != null) {
                            "${available.release.versionName} available"
                        } else {
                            "Version ${BuildConfig.VERSION_NAME}"
                        },
                        subtitleColor = if (available != null) MaterialTheme.colorScheme.primary
                        else Color.Unspecified,
                        onClick = onOpenUpdate,
                        trailing = { Chevron() },
                    )
                }

                if (isAdmin) {
                    SectionCard(title = "Server", flush = true) {
                        SettingsRow(
                            icon = Icons.Filled.Tune,
                            title = "Features",
                            subtitle = "Turn server modules on or off",
                            onClick = onOpenSettings,
                            trailing = { Chevron() },
                        )
                        SettingsRow(
                            icon = Icons.Filled.LiveTv,
                            title = "Jellyfin",
                            subtitle = "Stream detection and alerts",
                            onClick = onOpenJellyfin,
                            trailing = { Chevron() },
                        )
                    }
                }

                SectionCard(title = "Account", flush = true) {
                    SettingsRow(
                        icon = Icons.AutoMirrored.Filled.Logout,
                        title = "Log out",
                        onClick = vm::logout,
                    )
                    SettingsRow(
                        icon = Icons.Filled.Devices,
                        title = "Log out everywhere",
                        subtitle = "Revoke every token issued before now",
                        onClick = {
                            confirm = Confirm(
                                "Log out everywhere?",
                                "Every token issued before now will be revoked.",
                                vm::logoutAll,
                            )
                        },
                    )
                }

                val versionLine = buildString {
                    append("sys-mon-droid ${BuildConfig.VERSION_NAME}")
                    vm.serverVersion?.let { v -> append(" · server ${v.version} (${v.commit})") }
                }
                Text(
                    versionLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    vm.goingDown?.let { PowerGoingDownOverlay(it, onDismiss = { vm.goingDown = null }) }

    vm.streamingGate?.let { gate ->
        val restart = gate.kind == PowerKind.RESTART
        AlertDialog(
            onDismissRequest = { vm.streamingGate = null },
            title = { Text("Someone is watching Jellyfin") },
            text = {
                Text("${gate.message.trimEnd().removeSuffix(".")}. Are you sure you want to ${if (restart) "restart" else "shut down"}?")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.streamingGate = null
                    if (restart) vm.restart(force = true) else vm.shutdown(force = true)
                }) {
                    Text(
                        if (restart) "Restart anyway" else "Shut down anyway",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { vm.streamingGate = null }) { Text("Cancel") } },
        )
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title) },
            text = { Text(c.body) },
            confirmButton = {
                TextButton(onClick = { c.action(); confirm = null }) {
                    Text("Confirm", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private data class Confirm(val title: String, val body: String, val action: () -> Unit)

/** Who is signed in and where; replaces the old Connection card. */
@Composable
private fun AccountHeader(username: String, role: String, serverUrl: String) {
    val palette = SysMon.palette
    val shape = RoundedCornerShape(palette.cardRadius)
    val hostName = serverUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (palette.isInstrument) Modifier.border(1.dp, palette.hairline, shape) else Modifier),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(if (palette.isInstrument) shape else CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    username.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(username, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${role.replaceFirstChar { it.uppercase() }} · $hostName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Local, per-phone look: Classic, Instrument (the sys-mon web panel's style) or Minimal (a slimmer bottom bar). */
@Composable
private fun ThemeRow() {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val key by container.settings.themeStyle.collectAsStateWithLifecycle(initialValue = ThemeStyle.Classic.key)
    val current = ThemeStyle.fromKey(key)
    val side = if (SysMon.palette.isInstrument) 12.dp else 16.dp

    Column {
        SettingsRow(
            icon = Icons.Filled.Palette,
            title = "Theme",
            subtitle = "Instrument matches the web panel; Minimal keeps Health first",
        )
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(start = side + 36.dp + 16.dp, end = side, bottom = 8.dp),
        ) {
            ThemeStyle.entries.forEachIndexed { index, style ->
                SegmentedButton(
                    selected = style == current,
                    onClick = { scope.launch { container.settings.setThemeStyle(style.key) } },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeStyle.entries.size),
                ) { Text(style.label) }
            }
        }
    }
}
