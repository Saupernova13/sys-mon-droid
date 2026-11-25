package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun SearchChip.icon(): ImageVector? = when (this) {
    SearchChip.PHOTOS -> Icons.Filled.Image
    SearchChip.VIDEOS -> Icons.Filled.Videocam
    SearchChip.VOICE -> Icons.Filled.Mic
    SearchChip.DOCUMENTS -> Icons.Filled.Description
    else -> null
}

/**
 * The mode's filters in a bottom sheet with generous spacing: the Messages chips, or the Tags category,
 * picking choice, selected tags and refinements. Each change re-runs the query at once; "Done" closes the
 * sheet and "Clear all" resets what the mode has set.
 */
@Composable
internal fun SearchFiltersSheet(s: WhatsAppSearch, c: ChatColors, onDismiss: () -> Unit) {
    when (s.mode) {
        SearchMode.MESSAGES -> MessageFiltersSheet(s, c, onDismiss)
        SearchMode.TAGS -> TagsFiltersSheet(s, c, onDismiss)
        // Conversations has none; a mode change under an open sheet closes it.
        SearchMode.CONVERSATIONS -> LaunchedEffect(Unit) { onDismiss() }
    }
}

/** The scrolling sections above a pinned bar with "Clear all" and "Done". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiltersSheetFrame(c: ChatColors, clearEnabled: Boolean, onClear: () -> Unit, onDone: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.wall,
        contentColor = c.ink,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text("Filters", style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold))
                content()
                Spacer(Modifier.size(8.dp))
            }
            Row(
                Modifier.fillMaxWidth().background(c.inBg).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClear, enabled = clearEnabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Clear all", style = chatSecondaryStyle(c.accent, FontWeight.SemiBold))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onDone,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                ) { Text("Done", style = chatSecondaryStyle(c.onAccent, FontWeight.SemiBold)) }
            }
        }
    }
}

@Composable
private fun FilterSection(title: String, c: ChatColors, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = chatMetaStyle(c.meta).copy(fontWeight = FontWeight.SemiBold))
        content()
    }
}

/** Messages: who sent it (Mine / Theirs) and what it is (photos, videos, voice notes, documents); one of each may be on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageFiltersSheet(s: WhatsAppSearch, c: ChatColors, onDismiss: () -> Unit) {
    val sel = s.messages.chips
    FiltersSheetFrame(c, clearEnabled = !sel.none, onClear = s::clearAllFilters, onDone = onDismiss) {
        FilterSection("From", c) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (chip in SearchChip.entries.filter { it.group == ChipGroup.SENDER }) {
                    SearchPill(chip.label, sel.isOn(chip), c, { s.pressChip(chip) })
                }
            }
        }
        FilterSection("Type", c) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (chip in SearchChip.entries.filter { it.group == ChipGroup.MEDIA }) {
                    SearchPill(chip.label, sel.isOn(chip), c, { s.pressChip(chip) }, icon = chip.icon())
                }
            }
        }
    }
}

/**
 * Tags: what a tap adds, the selected tags (tap a chip to move it between the groups, its x to drop it), the
 * category that narrows browsing and the type-ahead, and the refinements: chat, dates, intensity, unsettled, sort.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsFiltersSheet(s: WhatsAppSearch, c: ChatColors, onDismiss: () -> Unit) {
    val t = s.tags
    val f = t.filters
    FiltersSheetFrame(
        c,
        clearEnabled = tagsFilterCount(f, t.cat) > 0 || !t.selection.isEmpty,
        onClear = s::clearAllFilters,
        onDone = onDismiss,
    ) {
        FilterSection("Tap adds", c) { TapAddsRow(t, c) }
        if (!t.selection.isEmpty) FilterSection("Selected", c) { SelectedTags(t, c) }
        FilterSection("Category", c) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchPill("All", t.cat.isEmpty(), c, { t.chooseCat("") })
                for ((id, label) in TAG_CATS) SearchPill(label, t.cat == id, c, { t.chooseCat(id) })
            }
        }
        FilterSection("Chat", c) {
            if (t.knownChats.isEmpty()) {
                Text("Chats show up here once there are results", style = chatSecondaryStyle(c.meta))
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchPill("Any chat", f.chat.isEmpty(), c, { t.setChat("") })
                    for ((jid, name) in t.knownChats) SearchPill(name, f.chat == jid, c, { t.setChat(jid) })
                }
            }
        }
        FilterSection("Date", c) {
            DateRow("From", f.since, c, t::setSince)
            DateRow("To", f.until, c, t::setUntil)
        }
        FilterSection("Minimum intensity", c) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchPill("Any", f.minIntensity == 0, c, { t.setMinIntensity(0) })
                for (n in TAG_INTENSITIES) SearchPill("$n+", f.minIntensity == n, c, { t.setMinIntensity(n) })
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = f.unresolved, role = Role.Switch, onValueChange = t::setUnresolved),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Only unsettled", style = chatSecondaryStyle(c.ink, FontWeight.SemiBold))
                Text("Conversations with something left open", style = chatMetaStyle(c.meta))
            }
            Switch(checked = f.unresolved, onCheckedChange = null)
        }
        FilterSection("Sort", c) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sort in TagSort.entries) {
                    // Most matched ranks by the Any of tags: without any it would mean nothing.
                    if (sort == TagSort.MATCHES && t.selection.any.isEmpty()) continue
                    SearchPill(sort.label, effectiveSort(t.selection, f) == sort, c, { t.setSort(sort) })
                }
            }
            if (t.selection.any.isEmpty()) Text("Most matched appears once you add an Any of tag", style = chatMetaStyle(c.meta))
        }
    }
}

/** "Tap adds: + Must have | ~ Any of | − Exclude": which group the next picked tag goes into (the answer to "how do I exclude"). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TapAddsRow(t: TagsSearchState, c: ChatColors, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (g in TagGroup.entries) SearchPill("${g.glyph} ${g.label}", t.addGroup == g, c, { t.chooseAddGroup(g) })
    }
}

/** The selected tags in their groups, each chip with its glyph, a tap to cycle its group and an x to drop it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedTags(t: TagsSearchState, c: ChatColors) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (g in TagGroup.entries) {
            val tags = t.selection.of(g)
            if (tags.isEmpty()) continue
            Text("${g.glyph} ${g.label}", style = chatMetaStyle(c.meta).copy(fontWeight = FontWeight.SemiBold))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (tag in tags) SelectedChip(tag, g, c, onCycle = { t.cycle(tag) }, onRemove = { t.remove(tag) })
            }
        }
    }
}

/** A selected tag: a 48 dp label zone that cycles its group and a 48 dp x zone that removes it, in one 32 dp pill. */
@Composable
private fun SelectedChip(tag: TagPick, g: TagGroup, c: ChatColors, onCycle: () -> Unit, onRemove: () -> Unit) {
    val tint = groupTint(g, c)
    val next = TagGroup.entries[(g.ordinal + 1) % TagGroup.entries.size]
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.heightIn(min = 48.dp)
                .semantics { role = Role.Button; contentDescription = "${g.label}: ${tag.label}. Tap to move to ${next.label}" }
                .clickable(onClick = onCycle),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${g.glyph} ${tag.label}",
                style = chatSecondaryStyle(c.ink, FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 240.dp).clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)).background(tint)
                    .heightIn(min = 32.dp).padding(start = 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            )
        }
        Box(
            Modifier.size(width = 36.dp, height = 48.dp)
                .semantics { role = Role.Button; contentDescription = "Remove ${tag.label}" }
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier.size(width = 32.dp, height = 32.dp).clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)).background(tint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = c.ink, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** A date row: its label, the day (or "Any date") and a clear button; a tap opens the date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(label: String, day: LocalDate?, c: ChatColors, onPick: (LocalDate?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp).clickable { open = true }.semantics { role = Role.Button },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = chatSecondaryStyle(c.ink), modifier = Modifier.width(56.dp))
            Text(
                day?.format(DayFormat) ?: "Any date",
                style = chatSecondaryStyle(if (day == null) c.meta else c.accent, FontWeight.SemiBold),
            )
        }
        if (day != null) {
            IconButton(onClick = { onPick(null) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Clear the $label date", tint = c.meta)
            }
        }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = day?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}
