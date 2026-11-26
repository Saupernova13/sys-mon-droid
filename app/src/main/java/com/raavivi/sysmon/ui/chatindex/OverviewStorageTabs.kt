package com.raavivi.sysmon.ui.chatindex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import com.raavivi.sysmon.ui.common.Reading
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.Severity

/** One group of overview counts in rows of two; a short last row keeps tile widths equal. */
@Composable
internal fun CountsCard(group: OverviewGroup) {
    SectionCard(title = group.title) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            group.cells.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { cell -> CountTile(cell, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** A number over its label (the number is the thing to read); failed cards above zero are flagged in the warning colour. */
@Composable
private fun CountTile(cell: OverviewCell, modifier: Modifier) {
    val failing = cell.label == "Cards failed" && cell.value != "0"
    Column(
        modifier.heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = "${cell.label}: ${cell.value}" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Reading(
            text = cell.value,
            state = if (failing) Severity.Warning else Severity.Nominal,
            identity = MaterialTheme.colorScheme.primary,
            large = false,
        )
        Text(cell.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Each chat-search database: file name, present / missing / unreadable with its size, and its tables with row counts. */
@Composable
internal fun StorageTab(st: ChatIndexState) {
    val s = st.storage
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val dbs = s.value?.dbs.orEmpty()
        when {
            s.phase == LoadPhase.LOADING || s.phase == LoadPhase.IDLE -> CiLoading()
            s.phase != LoadPhase.READY -> CiStateBlock(
                title = "Could not load the storage",
                hint = s.error,
                actionLabel = "Retry",
                onAction = s::load,
                isError = true,
            )
            dbs.isEmpty() -> CiStateBlock("No databases reported.", "Refresh to ask the server again.", actionLabel = "Refresh", onAction = s::load)
            else -> dbs.map(::storageCard).forEach { StorageCardView(it) }
        }
    }
}

@Composable
private fun StorageCardView(c: StorageCard) {
    SectionCard(title = c.file.ifBlank { c.key }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                storageLine(c),
                style = MaterialTheme.typography.bodyLarge,
                color = if (c.status == DbStatus.PRESENT) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            if (c.tables.isEmpty()) {
                Text(
                    if (c.status == DbStatus.PRESENT) "No tables." else "No tables to list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (t in c.tables) {
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        t.rows,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
