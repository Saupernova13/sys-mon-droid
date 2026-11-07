package com.raavivi.sysmon.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.raavivi.sysmon.core.model.FsJob
import com.raavivi.sysmon.ui.common.Severity
import com.raavivi.sysmon.ui.common.StatRow
import com.raavivi.sysmon.ui.common.UsageBar
import com.raavivi.sysmon.ui.common.formatBytes
import com.raavivi.sysmon.ui.common.formatRate
import com.raavivi.sysmon.ui.common.snake.SnakeProgress
import com.raavivi.sysmon.ui.common.snake.SnakeStatus
import com.raavivi.sysmon.ui.theme.SysMon

/**
 * Progress card for the job the view-model is showing. Dismissing only detaches:
 * the job keeps running and the Files header chip reopens it.
 */
@Composable
fun TransferProgressCard(vm: FilesViewModel) {
    val job = vm.shownJob ?: return
    TransferProgressCard(
        job = job,
        cancel = cancelView(vm.cancelStates[job.id] ?: CancelState.Idle),
        details = vm.transferDetails,
        onToggleDetails = { vm.toggleTransferDetails(!vm.transferDetails) },
        onCancel = { vm.cancelJob(job.id) },
        onClose = vm::detachJob,
    )
}

@Composable
fun TransferProgressCard(
    job: FsJob,
    cancel: CancelView,
    details: Boolean,
    onToggleDetails: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = SysMon.palette
    val finished = isTerminal(job.state)
    val fraction = progressFraction(job)
    val barState = when {
        job.state == "failed" -> Severity.Warning
        job.state == STATE_LOST -> Severity.Offline
        job.state == "cancelled" -> Severity.Caution
        job.errors.isNotEmpty() && finished -> Severity.Caution
        else -> Severity.Nominal
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f),
        ) {
            Column(Modifier.padding(16.dp)) {
                val title = when (job.state) {
                    "scanning", "running" -> "${jobVerb(job)} ${itemsLabel(job.sources.size)}"
                    else -> jobVerb(job)
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                SnakeProgress(
                    progress = if (job.state == "scanning") null else fraction,
                    status = when (job.state) {
                        "done" -> SnakeStatus.Done
                        "failed" -> SnakeStatus.Failed
                        "cancelled", STATE_LOST -> SnakeStatus.Cancelled
                        else -> SnakeStatus.Running
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(48.dp),
                )

                if (job.state == "scanning") {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp))
                    Text(
                        "Counting…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    UsageBar(
                        fraction = fraction,
                        color = MaterialTheme.colorScheme.primary,
                        state = barState,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${Math.round(fraction * 100)}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (palette.monoNumbers) FontFamily.Monospace else null,
                        )
                        val eta = etaSeconds(job)
                        if (eta != null) {
                            Text(
                                "${formatSeconds(eta)} left",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = if (palette.monoNumbers) FontFamily.Monospace else null,
                            )
                        }
                    }
                }

                when {
                    job.state == STATE_LOST -> Text(
                        "Lost track of this job - it may still be running on the host.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.sigWarn,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    finished && job.errors.isNotEmpty() && !details -> Text(
                        "${job.errors.size} ${if (job.errors.size == 1) "error" else "errors"}. Open Details to read them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.sigCrit,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                if (details) {
                    JobDetails(
                        job,
                        Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 8.dp),
                    )
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onToggleDetails) { Text(if (details) "Compact" else "Details") }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        if (!finished) {
                            TextButton(onClick = onCancel, enabled = cancel.enabled) { Text(cancel.label) }
                        }
                        TextButton(onClick = onClose) { Text(if (finished) "Close" else "Hide") }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobDetails(job: FsJob, modifier: Modifier = Modifier) {
    val palette = SysMon.palette
    Column(modifier) {
        val from = when (job.sources.size) {
            0 -> "—"
            1 -> job.sources.first()
            else -> "${job.sources.first()} and ${job.sources.size - 1} more"
        }
        StatRow("From", from)
        job.dest?.let { StatRow("To", it) }
        job.current?.takeIf { it.isNotBlank() && !isTerminal(job.state) }?.let { StatRow("Now", baseName(it)) }
        if (job.filesTotal != null && (job.op != "extract" && job.op != "compress" || (job.filesDone ?: 0) > 0 || job.state == "done")) {
            StatRow("Items", "${job.filesDone ?: 0} of ${job.filesTotal}")
        }
        if ((job.bytesTotal ?: 0) > 0) {
            StatRow("Data", "${formatBytes(job.bytesDone ?: 0)} of ${formatBytes(job.bytesTotal)}")
        }
        if (job.state == "running" && (job.speed ?: 0.0) > 0) {
            StatRow("Speed", formatRate(job.speed))
        }
        StatRow("Elapsed", formatSeconds(job.elapsed))
        if (!isTerminal(job.state)) StatRow("Remaining", formatSeconds(etaSeconds(job)))

        if (job.errors.isNotEmpty()) {
            Text(
                "Errors",
                style = MaterialTheme.typography.labelLarge,
                color = palette.sigCrit,
                modifier = Modifier.padding(top = 8.dp),
            )
            job.errors.forEach { e ->
                Text(
                    "${e.path}: ${e.message}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 3,
                )
            }
        }
        if (job.partial.isNotEmpty()) {
            Text(
                "Left behind, incomplete",
                style = MaterialTheme.typography.labelLarge,
                color = palette.sigWarn,
                modifier = Modifier.padding(top = 8.dp),
            )
            job.partial.forEach { p ->
                Text(
                    p,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
