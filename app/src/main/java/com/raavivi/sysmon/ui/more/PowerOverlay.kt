package com.raavivi.sysmon.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.raavivi.sysmon.ui.theme.SysMon
import kotlinx.coroutines.delay

/**
 * Full-screen "going down" notice shown after the server accepted a restart or
 * shutdown. A dialog window, so it also covers the bottom bar. Dismissing (Back,
 * or Close once the countdown ends) does not cancel anything on the host.
 */
@Composable
fun PowerGoingDownOverlay(state: GoingDown, onDismiss: () -> Unit) {
    val palette = SysMon.palette
    val restart = state.kind == PowerKind.RESTART
    val ink = if (restart) palette.sigWarn else palette.sigCrit
    var left by remember(state) { mutableIntStateOf(state.delaySeconds.coerceAtLeast(0)) }
    LaunchedEffect(state) {
        while (left > 0) {
            delay(1000)
            left -= 1
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (palette.bandHeaders) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(ink),
                )
            }
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (restart) "RESTART" else "SHUTDOWN",
                    color = ink,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = if (palette.monoNumbers) FontFamily.Monospace else FontFamily.Default,
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (left > 0) "in $left second${if (left == 1) "" else "s"}"
                    else "waiting for the machine to go down",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (left == 0) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Text(
                        "The host will still ${if (restart) "restart" else "shut down"}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
