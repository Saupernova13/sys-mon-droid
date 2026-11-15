package com.raavivi.sysmon.ui.sites

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.raavivi.sysmon.core.model.SiteSample
import com.raavivi.sysmon.core.model.SiteStatus
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import com.raavivi.sysmon.ui.theme.SysMon
import kotlinx.coroutines.delay

private const val REFRESH_MS = 30_000L

/** Current state of every site the server monitors; refreshes every 30 s while visible. */
@Composable
fun SitesScreen(onBack: () -> Unit) {
    val vm = rememberContainerViewModel { SitesViewModel(it) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var nowS by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }

    // Auto-refresh only while the screen is on top and the app is in the foreground.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(REFRESH_MS)
                vm.refresh()
            }
        }
    }
    // Re-anchor "Up 3 d" style durations whenever fresh data lands.
    LaunchedEffect(vm.data) { nowS = System.currentTimeMillis() / 1000 }

    val data = vm.data
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = "Sites", actions = {
            IconButton(onClick = { vm.refresh() }, enabled = !vm.loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        })

        vm.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                data == null -> Muted(if (vm.loading) "Loading sites..." else "Could not load sites.")
                !data.enabled -> Muted("Site monitoring is off in sys-mon Settings.")
                data.sites.isEmpty() -> Muted("No sites are being monitored.")
                else -> SectionCard(title = "Monitored sites") {
                    data.sites.forEachIndexed { index, site ->
                        if (index > 0) {
                            HorizontalDivider(
                                Modifier.padding(vertical = 10.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            )
                        }
                        SiteRow(site, nowS)
                    }
                }
            }
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

@Composable
private fun SiteRow(site: SiteStatus, nowS: Long) {
    val palette = SysMon.palette
    val color = when (site.state) {
        "up" -> palette.sigOk
        "down" -> palette.sigCrit
        else -> palette.offline
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                site.name.ifBlank { site.url },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                SiteText.status(site, nowS),
                style = MaterialTheme.typography.bodySmall,
                color = if (site.state == "down") palette.sigCrit else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SiteText.latency(site)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (site.history.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            Sparkline(
                site.history,
                okColor = palette.sigOk,
                failColor = palette.sigCrit,
                modifier = Modifier
                    .width(88.dp)
                    .height(28.dp),
            )
        }
    }
}

/** Latency line over the recent probes (oldest left); a failed probe gets a red tick. */
@Composable
private fun Sparkline(
    history: List<SiteSample>,
    okColor: Color,
    failColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val n = history.size
        val maxLat = history.filter { it.ok }.maxOfOrNull { it.latencyMs }?.coerceAtLeast(1L) ?: 1L
        val step = if (n > 1) size.width / (n - 1) else 0f
        val stroke = 1.5.dp.toPx()
        var prev: Offset? = null
        history.forEachIndexed { i, s ->
            val x = if (n > 1) i * step else size.width / 2
            if (s.ok) {
                val y = size.height - (s.latencyMs.toFloat() / maxLat) * (size.height - stroke) - stroke / 2
                val p = Offset(x, y)
                prev?.let { drawLine(okColor, it, p, strokeWidth = stroke, cap = StrokeCap.Round) }
                if (n == 1) drawCircle(okColor, radius = stroke, center = p)
                prev = p
            } else {
                drawLine(failColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                prev = null
            }
        }
    }
}
