package com.raavivi.sysmon.ui.alerts

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raavivi.sysmon.LocalAppContainer
import com.raavivi.sysmon.core.auth.SessionManager
import com.raavivi.sysmon.core.model.PowerDeviceRow
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import com.raavivi.sysmon.ui.theme.PowerColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Which plugs raise a notification, in two layers.
 *
 * *Watched* is the server's per-plug flag — shared with the web dashboard,
 * applies to every registered device, admin only. *Mute on this phone* is local:
 * the alert is still sent, this device just doesn't show it.
 */
@Composable
fun PlugAlertsScreen(onBack: () -> Unit) {
    val vm = rememberContainerViewModel { PlugAlertsViewModel(it) }
    val container = LocalAppContainer.current
    val role by container.session.role.collectAsStateWithLifecycle()
    val features by container.session.features.collectAsStateWithLifecycle()
    val isAdmin = role == SessionManager.ROLE_ADMIN
    val pushAvailable = features?.push == true

    vm.message?.let { LaunchedEffect(it) { delay(3000); vm.message = null } }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = "Plug alerts", actions = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        })

        (vm.error ?: vm.message)?.let {
            Text(
                it,
                color = if (vm.error != null) MaterialTheme.colorScheme.error else PowerColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DeviceOptInCard(pushAvailable)

            SectionCard(title = "Plugs", accent = PowerColor) {
                when {
                    vm.loading -> Muted("Loading plugs…")
                    vm.devices.isEmpty() -> Muted("No smart plugs are configured on the server.")
                }
                vm.devices.forEachIndexed { index, device ->
                    if (index > 0) {
                        HorizontalDivider(
                            Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        )
                    }
                    PlugAlertRow(vm, device, isAdmin)
                }
            }

            Text(
                "Watching is a server setting shared with the dashboard and every " +
                    "signed-in device. Muting only silences this phone — the alert is " +
                    "still sent, it just isn't shown here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlugAlertRow(vm: PlugAlertsViewModel, device: PowerDeviceRow, isAdmin: Boolean) {
    val isMuted = device.id in vm.muted
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.display, style = MaterialTheme.typography.bodyLarge)
                Text(
                    statusLine(vm, device, isMuted),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (vm.willNotify(device)) PowerColor
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = device.alert,
                onCheckedChange = { vm.setWatched(device.id, it) },
                enabled = isAdmin && !vm.busy && device.enabled,
            )
        }

        // Muting a plug the server isn't watching would do nothing; hide it
        // rather than offer a switch with no effect.
        if (device.alert && device.enabled) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (isMuted) Icons.AutoMirrored.Filled.VolumeOff
                    else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Mute on this phone",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = isMuted,
                    onCheckedChange = { vm.setMuted(device.id, it) },
                )
            }
        }
    }
}

private fun statusLine(vm: PlugAlertsViewModel, device: PowerDeviceRow, isMuted: Boolean): String = when {
    !device.enabled -> "Plug disabled on the server — never alerts"
    !device.alert -> "Not watched"
    isMuted -> "Watched, but muted on this phone"
    else -> {
        val label = device.alertLabel.ifBlank { device.display }
        "Alerts as \"Your $label is on\""
    }
}

/** The device-wide opt-in: without it this phone has no FCM registration at all
 *  and none of the per-plug switches below can reach it. */
@Composable
private fun DeviceOptInCard(pushAvailable: Boolean) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { enabled = container.settings.pushEnabledNow() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* registration proceeds regardless; alerts show once granted */ }

    SectionCard(title = "This device") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Receive plug alerts", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (pushAvailable) {
                        "Registers this phone for push. Off means no alerts reach it at all."
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

@Composable
private fun Muted(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
