package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How long the "Up to 5 themes at once" note stays. */
private const val CAP_NOTE_MS = 4_000L

/** Whether the chips row is on screen: a selection to turn off, or counts the last answer brought. */
internal fun themeRowShown(conv: ConvSearchState): Boolean =
    conv.selectedThemes.isNotEmpty() || (conv.phase == SearchPhase.DONE && conv.themeCounts.isNotEmpty())

/**
 * The theme chips over the Conversations results: the selected themes, Clear, then the rest, in one scrolling
 * row (8 dp above and below, 16 dp at the sides, 8 dp between chips), a thin progress line while a filtered
 * search runs and, under the row, the note that five themes is the most. Only selected chips use the accent.
 */
@Composable
internal fun ThemeChipsRow(conv: ConvSearchState, c: ChatColors, modifier: Modifier = Modifier) {
    val chips = remember(conv.selectedThemes, conv.themeCounts) { themeChips(conv.selectedThemes, conv.themeCounts) }
    val selectedCount = conv.selectedThemes.size
    // The note hides itself after a few seconds; each refused press restarts the wait.
    LaunchedEffect(conv.capNoteSeq) {
        if (conv.capNote) {
            delay(CAP_NOTE_MS)
            conv.dismissCapNote()
        }
    }
    Column(modifier) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            items(chips.take(selectedCount), key = { "on:" + it.name.lowercase() }) { ThemeChipView(it, c) { conv.pressTheme(it.name) } }
            if (selectedCount > 0) item(key = "clear") { ClearThemesChip(c, conv::clearThemes) }
            items(chips.drop(selectedCount), key = { "off:" + it.name.lowercase() }) { ThemeChipView(it, c) { conv.pressTheme(it.name) } }
        }
        if (conv.filtering) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.inBg)
        if (conv.capNote) {
            Text(
                THEME_CAP_NOTE,
                style = chatMetaStyle(c.meta),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** One theme: an M3 filter chip (32 dp, 48 dp touch target) with its count after the name in a quieter style. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeChipView(chip: ThemeChip, c: ChatColors, onClick: () -> Unit) {
    FilterChip(
        selected = chip.selected,
        onClick = onClick,
        // The chip reads "planning, 7 conversations"; the label's own texts stay out of the reading.
        modifier = Modifier.semantics { contentDescription = themeChipLabel(chip) },
        label = {
            Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
                Text(chip.name, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text(chip.count.toString(), style = MaterialTheme.typography.labelMedium, color = c.meta)
            }
        },
        leadingIcon = if (chip.selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = c.inBg,
            labelColor = c.ink,
            selectedContainerColor = c.accent.copy(alpha = 0.3f),
            selectedLabelColor = c.ink,
            selectedLeadingIconColor = c.ink,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = chip.selected,
            borderColor = c.hairline,
            selectedBorderColor = c.accent,
        ),
    )
}

/** "Clear" between the selected chips and the rest. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClearThemesChip(c: ChatColors, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = "Clear themes" },
        label = { Text("Clear", modifier = Modifier.clearAndSetSemantics { }) },
        colors = AssistChipDefaults.assistChipColors(containerColor = c.inBg, labelColor = c.ink),
        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = c.hairline),
    )
}
