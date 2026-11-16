package com.raavivi.sysmon.ui.notifications

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.raavivi.sysmon.LocalAppContainer
import com.raavivi.sysmon.core.auth.SessionManager
import com.raavivi.sysmon.core.push.FindMeAlertNotifier
import com.raavivi.sysmon.core.push.JellyfinAlertNotifier
import com.raavivi.sysmon.core.push.UpdateNotifier
import com.raavivi.sysmon.core.push.WatchdogNotifier
import com.raavivi.sysmon.ui.common.Chevron
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.SettingsRow
import com.raavivi.sysmon.ui.common.SwitchRow
import com.raavivi.sysmon.ui.theme.SysMon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** False when Android blocks this app's notifications (permission denied or switched off). */
private fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun needsPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

/**
 * One place for everything that reaches this phone: the push registration and
 * which kinds of alert it shows. Per-plug choices live on the Plug alerts screen.
 */
@Composable
fun NotificationsScreen(onBack: () -> Unit, onOpenPlugAlerts: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = SysMon.palette
    val role by container.session.role.collectAsStateWithLifecycle()
    val features by container.session.features.collectAsStateWithLifecycle()
    val pushAvailable = features?.push == true
    val findMeAvailable = role == SessionManager.ROLE_ADMIN && features?.findme == true
    val side = if (palette.isInstrument) 12.dp else 16.dp

    // Local copy so the switch flips instantly; the store catches up after registration.
    val stored by container.settings.pushEnabled.collectAsStateWithLifecycle(initialValue = false)
    var pushOn by remember { mutableStateOf(stored) }
    LaunchedEffect(stored) { pushOn = stored }

    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { allowed = notificationsAllowed(context) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed = notificationsAllowed(context) }

    fun requestPermissionIfNeeded() {
        if (needsPermission(context)) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val typesEnabled = pushAvailable && pushOn

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        ScreenHeader(title = "Notifications", actions = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        })

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionCard(title = "This phone", flush = true) {
                SwitchRow(
                    icon = Icons.Filled.NotificationsActive,
                    title = "Push notifications",
                    checked = pushOn && pushAvailable,
                    enabled = pushAvailable,
                    subtitle = if (!pushAvailable) {
                        "Unavailable - the server has no Firebase push key configured."
                    } else {
                        "Registers this phone with the server. Off means no alerts reach it."
                    },
                    onCheckedChange = { on ->
                        pushOn = on
                        if (on) {
                            requestPermissionIfNeeded()
                            scope.launch { container.pushRegistrar.enable() }
                        } else {
                            scope.launch { container.pushRegistrar.disable() }
                        }
                    },
                )
                if (pushOn && !allowed) {
                    SettingsRow(
                        icon = Icons.Filled.NotificationsOff,
                        title = "Android is blocking notifications",
                        subtitle = "Tap to allow them in system settings",
                        tint = palette.sigWarn,
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        trailing = { Chevron() },
                    )
                }
            }

            SectionCard(title = "Alerts", flush = true) {
                if (!typesEnabled) {
                    Text(
                        "Turn on push notifications to choose alerts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = side).padding(bottom = 4.dp),
                    )
                }
                SettingsRow(
                    icon = Icons.Filled.Power,
                    tint = palette.idPwr,
                    title = "Smart plugs",
                    subtitle = "Choose which plugs alert",
                    onClick = onOpenPlugAlerts,
                    trailing = { Chevron() },
                )
                AlertSwitch(
                    icon = Icons.Filled.LiveTv,
                    title = "Jellyfin streams",
                    subtitle = "Popup when someone starts streaming",
                    enabled = typesEnabled,
                    flow = container.settings.jellyfinAlerts,
                    save = { container.settings.setJellyfinAlerts(it) },
                    clear = { JellyfinAlertNotifier.cancelAll(it) },
                    onTurnedOn = ::requestPermissionIfNeeded,
                )
                AlertSwitch(
                    icon = Icons.Filled.MonitorHeart,
                    tint = palette.sigOk,
                    title = "Health",
                    subtitle = "A service goes down, is restarted or comes back",
                    enabled = typesEnabled,
                    flow = container.settings.healthAlerts,
                    save = { container.settings.setHealthAlerts(it) },
                    clear = { WatchdogNotifier.cancelAll(it) },
                    onTurnedOn = ::requestPermissionIfNeeded,
                )
                if (findMeAvailable) {
                    AlertSwitch(
                        icon = Icons.Filled.MyLocation,
                        title = "Find Me",
                        subtitle = "A tracked device stops reporting or is back",
                        enabled = typesEnabled,
                        flow = container.settings.findmeAlerts,
                        save = { container.settings.setFindmeAlerts(it) },
                        clear = { FindMeAlertNotifier.cancelAll(it) },
                        onTurnedOn = ::requestPermissionIfNeeded,
                    )
                }
                AlertSwitch(
                    icon = Icons.Filled.SystemUpdate,
                    title = "App updates",
                    subtitle = "A new sys-mon-droid build is ready",
                    enabled = typesEnabled,
                    flow = container.settings.updateNotifications,
                    save = { container.settings.setUpdateNotifications(it) },
                    clear = { UpdateNotifier.cancel(it) },
                    onTurnedOn = ::requestPermissionIfNeeded,
                )
            }
        }
    }
}

/** A per-phone switch for one kind of push popup (default on). */
@Composable
private fun AlertSwitch(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    flow: Flow<Boolean>,
    save: suspend (Boolean) -> Unit,
    clear: (Context) -> Unit,
    onTurnedOn: () -> Unit,
    tint: Color = Color.Unspecified,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val on by flow.collectAsStateWithLifecycle(initialValue = true)
    SwitchRow(
        icon = icon,
        tint = tint,
        title = title,
        subtitle = subtitle,
        checked = on,
        enabled = enabled,
        onCheckedChange = { value ->
            if (value) onTurnedOn()
            scope.launch {
                save(value)
                if (!value) clear(context)
            }
        },
    )
}
