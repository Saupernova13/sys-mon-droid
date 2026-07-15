package com.raavivi.sysmon.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raavivi.sysmon.BuildConfig
import com.raavivi.sysmon.LocalAppContainer
import com.raavivi.sysmon.core.auth.SessionManager
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.StatRow
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MoreScreen(
    onOpenTerminal: () -> Unit = {},
    onOpenScreen: () -> Unit = {},
    onOpenWhatsApp: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val vm = rememberContainerViewModel { MoreViewModel(it) }
    val container = LocalAppContainer.current
    val role by container.session.role.collectAsStateWithLifecycle()
    val features by container.session.features.collectAsStateWithLifecycle()
    val isAdmin = role == SessionManager.ROLE_ADMIN
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    vm.message?.let { msg -> LaunchedEffect(msg) { delay(3000); vm.message = null } }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = "More")

        vm.message?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "Connection") {
                StatRow("Server", vm.serverUrl)
                StatRow("User", vm.username)
                StatRow("Role", role)
            }

            NotificationsCard(pushAvailable = features?.push == true)

            // Everything below the connection card mutates the host, so the
            // read-only viewer role only ever sees Connection + Account.
            if (isAdmin) {
                SectionCard(title = "Host actions") {
                    if (features?.remoteControl == true) {
                        ActionButton("Launch remote control", Icons.Filled.SmartToy) { vm.remoteControl() }
                        Spacer(Modifier.height(8.dp))
                    }
                    ActionButton("Back up history", Icons.Filled.Backup) { vm.backup() }
                }

                SectionCard(title = "Power", accent = MaterialTheme.colorScheme.error) {
                    ActionButton("Restart host", Icons.Filled.RestartAlt) {
                        confirm = Confirm("Restart host?", "The host will reboot shortly.", vm::restart)
                    }
                    Spacer(Modifier.height(8.dp))
                    ActionButton("Shut down host", Icons.Filled.PowerSettingsNew) {
                        confirm = Confirm("Shut down host?", "The host will power off shortly.", vm::shutdown)
                    }
                }

                SectionCard(title = "Tools") {
                    ActionButton("Terminal", Icons.Filled.Terminal, onOpenTerminal)
                    Spacer(Modifier.height(8.dp))
                    ActionButton("Screen share", Icons.AutoMirrored.Filled.ScreenShare, onOpenScreen)
                    if (features?.whatsapp == true) {
                        Spacer(Modifier.height(8.dp))
                        ActionButton("WhatsApp", Icons.AutoMirrored.Filled.Chat, onOpenWhatsApp)
                    }
                    Spacer(Modifier.height(8.dp))
                    ActionButton("Server settings", Icons.Filled.Settings, onOpenSettings)
                }
            }

            SectionCard(title = "Account") {
                ActionButton("Log out", Icons.AutoMirrored.Filled.Logout) { vm.logout() }
                Spacer(Modifier.height(8.dp))
                ActionButton("Log out everywhere", Icons.AutoMirrored.Filled.Logout) {
                    confirm = Confirm(
                        "Log out everywhere?",
                        "Every token issued before now will be revoked.",
                        vm::logoutAll,
                    )
                }
            }

            val versionLine = buildString {
                append("sys-mon-droid ${BuildConfig.VERSION_NAME}")
                vm.serverVersion?.let { v ->
                    append(" · server ${v.version} (${v.commit})")
                }
            }
            Text(
                versionLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
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

/**
 * Heater-alert toggle: subscribes this device to Firebase push notifications so a
 * "Your heater is on" notification fires no matter how the plug was switched. The
 * backend watches the relay and pushes on/off; enabling registers this device's
 * FCM token, disabling unregisters it. Enabling asks for POST_NOTIFICATIONS on
 * Android 13+. Disabled with a hint when the server has no push key configured.
 */
@Composable
private fun NotificationsCard(pushAvailable: Boolean) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { enabled = container.settings.pushEnabledNow() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* registration proceeds regardless; alerts show once granted */ }

    SectionCard(title = "Notifications") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Heater alert", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (pushAvailable) {
                        "Push a notification while the heater plug is on, with live usage and a Stop button."
                    } else {
                        "Unavailable — the server has no Firebase push key configured."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled && pushAvailable,
                enabled = pushAvailable,
                onCheckedChange = { on ->
                    enabled = on
                    if (on) {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        scope.launch { container.pushRegistrar.enable() }
                    } else {
                        scope.launch { container.pushRegistrar.disable() }
                    }
                },
            )
        }
    }
}

private data class Confirm(val title: String, val body: String, val action: () -> Unit)

@Composable
private fun ActionButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
