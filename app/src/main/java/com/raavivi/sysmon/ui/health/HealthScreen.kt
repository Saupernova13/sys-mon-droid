package com.raavivi.sysmon.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.raavivi.sysmon.LocalAppContainer
import com.raavivi.sysmon.core.auth.SessionManager
import com.raavivi.sysmon.core.model.WatchdogEvent
import com.raavivi.sysmon.core.model.WatchdogTarget
import com.raavivi.sysmon.core.push.WatchdogAlert
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import com.raavivi.sysmon.ui.theme.SysMon
import com.raavivi.sysmon.ui.theme.LocalThemeStyle
import com.raavivi.sysmon.ui.theme.ThemeStyle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

private const val REFRESH_MS = 30_000L
private const val PAUSE_MINUTES = 30

/** Watchdog targets and their recent events; refreshes every 30 s while visible. */
@Composable
fun HealthScreen(onBack: (() -> Unit)? = null) {
    val vm = rememberContainerViewModel { HealthViewModel(it) }
    val container = LocalAppContainer.current
    val role by container.session.role.collectAsStateWithLifecycle()
    val isAdmin = role == SessionManager.ROLE_ADMIN
    val features by container.session.features.collectAsStateWithLifecycle()
    // Auto fix is a Minimal-theme card, only when the server has it on.
    val showAutoFix = LocalThemeStyle.current == ThemeStyle.Minimal && isAdmin && features?.autofix == true
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Auto-refresh only while the screen is on top and the app is in the foreground.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(REFRESH_MS)
                vm.refresh()
            }
        }
    }
    // Poll Auto fix only while the screen is on top and the app is in the foreground.
    if (showAutoFix) {
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                vm.autoFix.attach()
                try {
                    awaitCancellation()
                } finally {
                    vm.autoFix.detach()
                }
            }
        }
    }
    vm.message?.let { LaunchedEffect(it) { delay(3000); vm.message = null } }

    val status = vm.status
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = "Health", actions = {
            IconButton(onClick = { vm.refresh() }, enabled = !vm.loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
            onBack?.let { back ->
                IconButton(onClick = back) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        })

        (vm.error ?: vm.message)?.let {
            Text(
                it,
                color = if (vm.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showAutoFix && !vm.autoFix.hidden) AutoFixCard(vm.autoFix)
            when {
                status == null && vm.error != null -> Muted("The watchdog is not reachable.")
                status == null -> Muted("Loading...")
                status.targets.isEmpty() -> Muted("The watchdog has no targets.")
                else -> SectionCard(title = "Targets") {
                    status.targets.forEachIndexed { index, t ->
                        if (index > 0) Divider()
                        TargetRow(t, isAdmin, busy = vm.busyId == t.id,
                            onPause = { vm.pause(t.id, t.name, PAUSE_MINUTES) },
                            onResume = { vm.resume(t.id, t.name) })
                    }
                }
            }

            SectionCard(title = "Recent events") {
                if (vm.events.isEmpty()) Muted("No events yet.")
                vm.events.forEachIndexed { index, e ->
                    if (index > 0) Divider()
                    EventRow(e)
                }
            }
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        Modifier.padding(vertical = 10.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
    )
}

@Composable
private fun Muted(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TargetRow(
    t: WatchdogTarget,
    isAdmin: Boolean,
    busy: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val palette = SysMon.palette
    val color = when (t.state) {
        "healthy" -> palette.sigOk
        "degraded", "healing" -> palette.sigWarn
        "down", "given_up" -> palette.sigCrit
        else -> palette.offline
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t.name.ifBlank { t.id },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    HealthText.stateText(t),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (t.state == "down" || t.state == "given_up") palette.sigCrit else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HealthText.restartsText(t)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isAdmin) {
                if (t.state == "paused") {
                    OutlinedButton(onClick = onResume, enabled = !busy) { Text("Resume") }
                } else {
                    OutlinedButton(onClick = onPause, enabled = !busy) { Text("Pause 30 min") }
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: WatchdogEvent) {
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                "${e.name.ifBlank { e.id }}: ${WatchdogAlert.label(e.event)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                HealthText.dayClock(e.ts),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (e.detail.isNotBlank()) {
            Text(
                e.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
