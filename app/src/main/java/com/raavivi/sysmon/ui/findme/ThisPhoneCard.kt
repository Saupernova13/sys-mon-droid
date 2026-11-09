package com.raavivi.sysmon.ui.findme

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raavivi.sysmon.core.tracker.data.TrackerState
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.theme.SysMon

/** One fix button per permission step, in the order the pairing walk offers them. */
enum class PermissionStep(val label: String) {
    Location("Precise location"),
    Background("Location: Allow all the time"),
    Notifications("Notifications"),
    Battery("Battery optimisation exemption"),
    FullScreen("Full-screen alerts (Ring)"),
}

/**
 * "This phone" card of the Find Me screen: the built-in tracker. Unpaired it is a single
 * "Track this phone" switch; paired it shows tracking state, last fix and upload, the
 * queue, push, Report now, Unpair and the permission checklist.
 */
@Composable
fun ThisPhoneCard(
    vm: FindMeViewModel,
    onFix: (PermissionStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = vm.phone.collectAsStateWithLifecycle().value ?: return
    val queued by vm.queued.collectAsStateWithLifecycle()
    val palette = SysMon.palette
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    val missing = PermissionStep.entries.filter { !stepOk(vm, it) }

    SectionCard(
        title = "This phone",
        modifier = modifier,
        trailing = if (state.paired) {
            {
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse this phone" else "Expand this phone",
                    )
                }
            }
        } else {
            null
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.notice?.let { notice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, color = palette.sigWarn, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::dismissPhoneNotice) { Text("Dismiss") }
                }
            }

            if (!state.paired && !vm.phoneIsAdmin) {
                Text(
                    "Only an admin can add this phone to Find Me.",
                    color = palette.quietInk,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Track this phone", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            summary(state),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.paired && !state.tracking) palette.sigWarn else palette.quietInk,
                        )
                    }
                    if (vm.phoneBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Switch(
                            checked = state.paired && state.tracking,
                            onCheckedChange = vm::trackThisPhone,
                        )
                    }
                }
            }
            vm.phoneError?.let {
                Text(it, color = palette.sigCrit, style = MaterialTheme.typography.bodySmall)
            }

            if (state.paired) {
                if (missing.isNotEmpty() && !expanded) {
                    Text(
                        "${missing.size} permission${if (missing.size == 1) "" else "s"} to fix, expand for details",
                        color = palette.sigWarn,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (expanded) {
                    val maxH = (LocalConfiguration.current.screenHeightDp * 0.4f).dp
                    Column(
                        Modifier
                            .heightIn(max = maxH)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Details(vm, state, queued, onFix) { confirmUnpair = true }
                    }
                }
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair this phone?") },
            text = { Text("It stops sharing its location and is removed from Find Me.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    vm.unpairThisPhone()
                }) { Text("Unpair", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Details(
    vm: FindMeViewModel,
    state: TrackerState,
    queued: Int,
    onFix: (PermissionStep) -> Unit,
    onUnpair: () -> Unit,
) {
    val palette = SysMon.palette
    Text(
        "${state.deviceName} -> ${state.serverName ?: state.server}",
        style = MaterialTheme.typography.bodySmall,
        color = palette.quietInk,
    )
    if (!state.tracking) {
        Text("Paused: locate requests are refused. Ring still works.", color = palette.sigWarn)
    }
    Line("Last fix", lastFixText(state))
    Line(
        "Last upload",
        state.lastUploadMessage?.let { msg ->
            val at = state.lastUploadAtMs?.let { relative(it) }.orEmpty()
            "$msg ($at)"
        } ?: "never",
    )
    Line("Queued fixes", queued.toString())
    Line("Report interval", "${state.intervalS} s (set per device)")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Push", modifier = Modifier.weight(0.4f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (vm.pushAvailable) "on" else "off (commands arrive with the next report)",
            color = if (vm.pushAvailable) palette.sigOk else palette.sigWarn,
            modifier = Modifier.weight(0.6f),
        )
    }

    Button(
        onClick = vm::reportNow,
        enabled = state.tracking && vm.perms.fineLocation,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Report now") }

    HorizontalDivider()
    Text("Checklist", style = MaterialTheme.typography.labelSmall, color = palette.quietInk)
    PermissionStep.entries.forEach { step ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(step.label, modifier = Modifier.weight(1f))
            if (stepOk(vm, step)) {
                Text("OK", color = palette.sigOk)
            } else {
                OutlinedButton(onClick = { onFix(step) }) { Text("Fix") }
            }
        }
    }
    Text(
        "Tracking uses its own device token, so signing out of sys-mon keeps it running.",
        style = MaterialTheme.typography.bodySmall,
        color = palette.quietInk,
        modifier = Modifier.padding(top = 2.dp),
    )
    OutlinedButton(onClick = onUnpair, modifier = Modifier.fillMaxWidth()) { Text("Unpair") }
}

@Composable
private fun Line(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(0.4f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(0.6f))
    }
}

/** Whether [step] is satisfied, per the live permission snapshot in [vm]. */
fun stepOk(vm: FindMeViewModel, step: PermissionStep): Boolean = when (step) {
    PermissionStep.Location -> vm.perms.fineLocation
    PermissionStep.Background -> vm.perms.backgroundLocation
    PermissionStep.Notifications -> vm.perms.notifications
    PermissionStep.Battery -> vm.perms.batteryExempt
    PermissionStep.FullScreen -> vm.perms.fullScreen
}

private fun summary(state: TrackerState): String = when {
    !state.paired -> "Share this phone's location with sys-mon"
    !state.tracking -> "Paused"
    else -> "Sharing: " + lastFixText(state)
}

private fun lastFixText(state: TrackerState): String {
    val at = state.lastFixAtMs ?: return "no fix yet"
    val acc = state.lastFixAccuracyM?.let { ", +/- ${it.toInt()} m" }.orEmpty()
    return relative(at) + acc
}

private fun relative(ms: Long): String =
    DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString()
