package com.raavivi.sysmon.ui.logs

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.SettingsRow
import com.raavivi.sysmon.ui.common.SwitchRow
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import com.raavivi.sysmon.ui.theme.SysMon

/** On-device logs: on/off switch, folder, share (zip + share sheet) and clear. */
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val vm = rememberContainerViewModel { LogsViewModel(it) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    vm.message?.let { msg ->
        LaunchedEffect(msg) {
            snackbar.showSnackbar(msg)
            vm.message = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenHeader(title = "Logs", actions = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            })

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionCard(title = "This phone", flush = true) {
                    SwitchRow(
                        icon = Icons.Filled.Description,
                        title = "Write logs",
                        checked = vm.loggingEnabled,
                        onCheckedChange = vm::setLogging,
                        subtitle = if (vm.loggingEnabled) "Kept for 24 hours, then deleted" else "Logging is off",
                        subtitleColor = if (vm.loggingEnabled) Color.Unspecified else SysMon.palette.sigWarn,
                    )
                    SettingsRow(icon = Icons.Filled.Folder, title = "Folder", subtitle = vm.logsLocation)
                    SettingsRow(
                        icon = Icons.Filled.Share,
                        title = "Share logs",
                        subtitle = "Zip and send with any app",
                        enabled = !vm.logsBusy,
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
                    )
                    SettingsRow(
                        icon = Icons.Filled.DeleteSweep,
                        title = "Clear logs",
                        enabled = !vm.logsBusy,
                        onClick = vm::clearLogs,
                    )
                }
                Text(
                    "No tokens, request bodies or coordinates are logged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
