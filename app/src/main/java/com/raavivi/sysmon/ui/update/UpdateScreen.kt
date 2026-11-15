package com.raavivi.sysmon.ui.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raavivi.sysmon.BuildConfig
import com.raavivi.sysmon.LocalAppContainer
import com.raavivi.sysmon.core.update.UpdateCheck
import com.raavivi.sysmon.core.update.UpdateRelease
import com.raavivi.sysmon.core.update.UpdateStep
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.StatRow
import com.raavivi.sysmon.ui.common.formatBytes

/** Installed vs. latest build, with one button that walks check -> download -> install. */
@Composable
fun UpdateScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val updates = container.updates
    val ui by updates.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (updates.ui.value.check == UpdateCheck.Unchecked) updates.check()
    }

    val check = ui.check
    val step = ui.step
    val busy = check == UpdateCheck.Checking || step is UpdateStep.Downloading || step == UpdateStep.Installing

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = "App updates", actions = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        })

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(title = "Installed") {
                StatRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            }

            when (check) {
                is UpdateCheck.Available -> ReleaseCard(check.release)
                UpdateCheck.Disabled -> Note("Updates are off in sys-mon Settings (App updates).")
                UpdateCheck.UpToDate -> Note("You are on the latest version.")
                UpdateCheck.Checking -> Note("Checking...")
                UpdateCheck.Unchecked -> Note("Not checked yet.")
                is UpdateCheck.Error -> Text(
                    check.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            when (step) {
                is UpdateStep.Downloading -> {
                    val frac = if (step.total > 0) (step.done.toFloat() / step.total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    Note("Downloading ${formatBytes(step.done)} / ${formatBytes(step.total)}")
                }
                UpdateStep.Installing -> Note("Installing... confirm on the system prompt if it appears.")
                UpdateStep.Done -> Note("Installed. The app restarts on the new version.")
                UpdateStep.NeedsPermission -> Note(
                    "Android needs your permission before this app can install updates. " +
                        "Allow \"Install unknown apps\" for sys-mon-droid in the next screen, " +
                        "come back and tap the button again.",
                )
                is UpdateStep.Failed -> Text(
                    step.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                UpdateStep.Idle -> Unit
            }

            val available = check is UpdateCheck.Available
            if (available) {
                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (!updates.canInstall()) {
                            updates.downloadAndInstall() // sets NeedsPermission
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } else {
                            updates.resetStep()
                            updates.downloadAndInstall()
                        }
                    },
                ) { Text(if (step is UpdateStep.Failed) "Retry download and install" else "Download and install") }
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { updates.resetStep(); updates.check() },
                ) { Text("Check now") }
            } else {
                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { updates.resetStep(); updates.check() },
                ) { Text("Check now") }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReleaseCard(r: UpdateRelease) {
    SectionCard(title = "Latest") {
        StatRow("Version", "${r.versionName} (${r.versionCode})")
        if (r.size > 0) StatRow("Size", formatBytes(r.size))
        r.builtAt?.let { StatRow("Built", it) }
        r.notes?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
