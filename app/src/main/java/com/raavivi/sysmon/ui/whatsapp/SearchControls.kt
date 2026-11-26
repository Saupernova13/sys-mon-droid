package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.ui.theme.SysMon

/**
 * The one row under the mode switch: the "Filters" pill (with how many are set), in Tags the selected tags as
 * a compact scrolling row, and the slim status chip at the end. Nothing else is pinned above the results.
 */
@Composable
internal fun SearchControls(s: WhatsAppSearch, c: ChatColors, onFilters: () -> Unit, onStatus: () -> Unit) {
    val inConv = !s.conv.available || s.mode == SearchMode.CONVERSATIONS
    val chip = statusChip(s.conv.status, inConv)
    val hasFilters = modeHasFilters(s.mode)
    val tagChips = if (s.mode == SearchMode.TAGS) selectedTagChips(s.tags.selection) else emptyList()
    val deepShown = s.mode == SearchMode.CONVERSATIONS && s.conv.available
    if (!hasFilters && !chip.visible && !deepShown) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (deepShown) DeepPill(s.conv, c)
        if (hasFilters) {
            val n = s.activeFilters
            SearchPill(
                filtersLabel(n), on = n > 0, c = c, onClick = onFilters, icon = Icons.Filled.Tune,
                modifier = Modifier.semantics { contentDescription = filtersDescription(n) },
            )
        }
        if (tagChips.isNotEmpty()) SelectedTagsRow(tagChips, c, onRemove = { s.tags.remove(it) }, modifier = Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
        if (chip.visible) StatusChipView(chip, c, onStatus)
    }
}

/** The selected tags as one scrolling row; a tap drops a tag (moving it between groups is in the Filters sheet). */
@Composable
private fun SelectedTagsRow(chips: List<SelectedTagChip>, c: ChatColors, onRemove: (TagPick) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
        items(chips, key = { it.tag.key }) { chip ->
            Box(
                Modifier.heightIn(min = 48.dp)
                    .semantics { role = Role.Button; contentDescription = "${chip.group.label}: ${chip.tag.label}. Tap to remove" }
                    .clickable { onRemove(chip.tag) },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.clip(CircleShape).background(groupTint(chip.group, c)).heightIn(min = 32.dp).padding(start = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tagChipText(chip),
                        style = chatSecondaryStyle(c.ink, FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Icon(Icons.Filled.Close, contentDescription = null, tint = c.ink, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** The pill tint of a group: decoration only, the glyph says which group it is. */
@Composable
internal fun groupTint(g: TagGroup, c: ChatColors): Color = when (g) {
    TagGroup.ALL -> c.accent.copy(alpha = 0.3f)
    TagGroup.ANY -> c.accent.copy(alpha = 0.15f)
    TagGroup.NONE -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
}

/** A small dot and a short phrase ("Cards writing", "Index up to date"); a tap opens the full status. */
@Composable
private fun StatusChipView(chip: StatusChip, c: ChatColors, onClick: () -> Unit) {
    val dot = when (chip.tone) {
        StatusTone.BUSY -> c.accent
        StatusTone.OK -> SysMon.palette.sigOk
        StatusTone.ATTENTION -> MaterialTheme.colorScheme.tertiary
        StatusTone.IDLE -> c.meta
    }
    Box(
        Modifier.heightIn(min = 48.dp)
            .semantics { role = Role.Button; contentDescription = "Search status: ${chip.text}. Opens details" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(CircleShape).background(c.inBg).heightIn(min = 32.dp).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.size(8.dp))
            Text(chip.text, style = chatSecondaryStyle(c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
        }
    }
}
