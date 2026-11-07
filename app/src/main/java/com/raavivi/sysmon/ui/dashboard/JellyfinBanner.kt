package com.raavivi.sysmon.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.JellyfinStreamRow
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.formatStreamPosition
import com.raavivi.sysmon.ui.theme.SysMon

/**
 * Who is watching what on Jellyfin (the web dashboard's stream strip). Classic
 * is a plain card; Instrument is a band with a neutral identity.
 */
@Composable
fun JellyfinBanner(streams: List<JellyfinStreamRow>, error: String?) {
    val p = SysMon.palette
    SectionCard(
        title = "Jellyfin",
        accent = if (p.isInstrument) p.quietInk else MaterialTheme.colorScheme.primary,
    ) {
        streams.forEach { s -> StreamRow(s) }
        error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = p.quietInk)
        }
    }
}

@Composable
private fun StreamRow(s: JellyfinStreamRow) {
    val p = SysMon.palette
    val pos = formatStreamPosition(s.positionS, s.runtimeS)
    val where = listOfNotNull(s.device, s.client).filter { it.isNotBlank() }.joinToString(" / ")
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row {
            Text(
                listOfNotNull(s.user?.takeIf { it.isNotBlank() }, s.title).joinToString(" - "),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (s.paused) {
                Spacer(Modifier.size(8.dp))
                Text("paused", style = MaterialTheme.typography.labelSmall, color = p.sigWarn)
            }
        }
        if (pos.isNotEmpty()) {
            Text(
                pos,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = if (p.monoNumbers) FontFamily.Monospace else FontFamily.Default,
                color = p.quietInk,
            )
        }
        if (where.isNotEmpty()) {
            Text(where, style = MaterialTheme.typography.labelSmall, color = p.quietInk)
        }
    }
}
