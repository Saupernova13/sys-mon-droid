package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The tonal bar surface the conversation header uses, so the search bars read as the same chrome. */
private fun ChatColors.barBg(): Color = lerp(wall, inBg, 0.7f)

/**
 * While in-chat search is open this replaces the conversation header: back (closes the search) and the query
 * field. The counter and the stepping arrows are in [InChatSearchBottomBar], in the thumb zone.
 */
@Composable
internal fun InChatSearchTopBar(state: InChatSearchState, c: ChatColors, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(c.barBg()).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { state.close() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search", tint = c.ink)
        }
        ChatSearchField(
            value = state.query,
            onValueChange = state::onQueryChange,
            c = c,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            placeholder = "Search in this chat",
            onSearch = { state.step(1) },
            // A bar opened by a hit already has its query; do not pop the keyboard over the message.
            autoFocus = state.query.isEmpty(),
        )
    }
}

/**
 * Replaces the composer while in-chat search is open: the counter ("3/12", "No matches"; a polite live
 * region) and the older / newer arrows, disabled rather than hidden at the ends. It rides above the keyboard.
 */
@Composable
internal fun InChatSearchBottomBar(state: InChatSearchState, c: ChatColors, modifier: Modifier = Modifier) {
    val buttons = state.buttons
    Row(
        modifier.fillMaxWidth().background(c.barBg()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            state.countLabel,
            style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        ArrowButton(Icons.Filled.KeyboardArrowUp, "Older match", c, !buttons.olderOff) { state.step(1) }
        ArrowButton(Icons.Filled.KeyboardArrowDown, "Newer match", c, !buttons.newerOff) { state.step(-1) }
    }
}

@Composable
private fun ArrowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    c: ChatColors,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = description, tint = if (enabled) c.ink else c.ink.copy(alpha = 0.38f))
    }
}
