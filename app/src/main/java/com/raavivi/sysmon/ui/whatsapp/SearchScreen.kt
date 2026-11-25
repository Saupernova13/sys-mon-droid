package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The full-screen Search view inside the WhatsApp tab: a query field, the Conversations | Messages switch
 * (when chat search is on), filter chips (Messages only), a status card for the index and topic-card lines
 * (Conversations) and the results. All state lives in [WhatsAppSearch], so it survives opening a hit and
 * coming back.
 */
@Composable
internal fun SearchScreen(vm: WhatsAppViewModel) {
    val s = vm.search
    val c = rememberChatColors()
    // The status is read when the view appears and polled only while it is on screen.
    DisposableEffect(s) {
        s.viewShown()
        onDispose { s.viewHidden() }
    }
    val busy = when (s.mode) {
        SearchMode.CONVERSATIONS -> s.conv.phase
        SearchMode.MESSAGES -> s.messages.phase
        SearchMode.TAGS -> SearchPhase.DONE
    }
    var helpOpen by remember { mutableStateOf(false) }
    val tagsMode = s.mode == SearchMode.TAGS
    val loading = if (tagsMode) s.tags.phase == TagsPhase.LOADING
    else s.query.isNotBlank() && (busy == SearchPhase.LOADING || busy == SearchPhase.IDLE)
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { s.closeView() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to chats", tint = c.ink)
            }
            ChatSearchField(
                value = if (tagsMode) s.tags.input else s.query,
                onValueChange = s::onQueryChange,
                c = c,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(end = 8.dp),
                placeholder = when (s.mode) {
                    SearchMode.CONVERSATIONS -> "Search conversations"
                    SearchMode.MESSAGES -> "Search messages"
                    SearchMode.TAGS -> "Find a tag"
                },
                onSearch = s::submit,
                // Tags opens on its browse lists: the keyboard comes when the field is tapped.
                autoFocus = !tagsMode && s.query.isEmpty(),
            )
            HelpButton(c) { helpOpen = true }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.inBg)
        vm.message?.let { Toast(it) }
        if (s.modes.isNotEmpty()) ModeSwitch(s, c)
        if (s.mode == SearchMode.MESSAGES) ChipRow(s, c)
        if (tagsMode) TagsCategoryRow(s.tags, c)
        // Conversations shows its status as the first item of the scrolling content; here it only
        // appears when there is no conversation mode yet (Messages, with an index never built).
        if (s.mode == SearchMode.MESSAGES) StatusCard(s, c)
        when (s.mode) {
            SearchMode.CONVERSATIONS -> ConversationResults(vm, c, Modifier.weight(1f))
            SearchMode.MESSAGES -> MessageResults(vm, c, Modifier.weight(1f))
            SearchMode.TAGS -> TagsResults(vm, c, Modifier.weight(1f))
        }
    }
    if (helpOpen) SearchHelpSheet(s.mode, c, onExample = s::openFor, onDismiss = { helpOpen = false })
    CardSettingsSheet(vm, s.conv.cardSettings, c)
}

/** A rounded toggle for the filter chips: a 48 dp touch target around a 32 dp pill; accent fill when on. */
@Composable
internal fun SearchPill(label: String, on: Boolean, c: ChatColors, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .semantics { role = Role.Button; selected = on }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(CircleShape).background(if (on) c.accent.copy(alpha = 0.3f) else c.inBg).heightIn(min = 32.dp).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = if (on) c.ink else c.meta, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = chatSecondaryStyle(c.ink, if (on) FontWeight.SemiBold else FontWeight.Normal))
        }
    }
}

private fun SearchChip.icon(): ImageVector? = when (this) {
    SearchChip.PHOTOS -> Icons.Filled.Image
    SearchChip.VIDEOS -> Icons.Filled.Videocam
    SearchChip.VOICE -> Icons.Filled.Mic
    SearchChip.DOCUMENTS -> Icons.Filled.Description
    else -> null
}

private fun modeLabel(m: SearchMode): String = when (m) {
    SearchMode.CONVERSATIONS -> "Conversations"
    SearchMode.MESSAGES -> "Messages"
    SearchMode.TAGS -> "Tags"
}

/** Conversations | Messages | Tags (those on offer) as a Material3 segmented control directly under the field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(s: WhatsAppSearch, c: ChatColors) {
    val modes = s.modes.map { it to modeLabel(it) }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        modes.forEachIndexed { i, (mode, label) ->
            SegmentedButton(
                selected = s.mode == mode,
                onClick = { s.setMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                modifier = Modifier.heightIn(min = 48.dp),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = c.accent.copy(alpha = 0.3f),
                    activeContentColor = c.ink,
                    activeBorderColor = c.accent,
                    inactiveContainerColor = c.inBg,
                    inactiveContentColor = c.ink,
                    inactiveBorderColor = c.hairline,
                ),
                label = { Text(label, style = chatSecondaryStyle(c.ink, if (s.mode == mode) FontWeight.SemiBold else FontWeight.Normal)) },
            )
        }
    }
}

@Composable
private fun ChipRow(s: WhatsAppSearch, c: ChatColors) {
    val sel = s.messages.chips
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SearchPill("All", sel.none, c, { s.pressChip(null) }) }
        items(SearchChip.entries) { chip -> SearchPill(chip.label, sel.isOn(chip), c, { s.pressChip(chip) }, icon = chip.icon()) }
    }
}

/** The index line and the topic-cards line in one tonal card: both belong to Conversations, or to a view with no such mode yet. */
@Composable
internal fun StatusCard(s: WhatsAppSearch, c: ChatColors) {
    val inConv = !s.conv.available || s.mode == SearchMode.CONVERSATIONS
    val status = s.conv.status
    val index = indexView(status, inConv)
    val cards = cardsView(status, inConv)
    if (!index.visible && !cards.visible) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(c.inBg).padding(vertical = 4.dp)) {
        if (index.visible) StatusLine(Icons.Filled.Sync, index, c, busy = s.conv.indexBusy, onClick = s.conv::requestIndex)
        if (cards.visible) {
            StatusLine(
                Icons.Filled.AutoAwesome, cards, c, busy = s.conv.cardsBusy,
                onClick = { s.conv.toggleCards(cards.running) },
                onSettings = s.conv.cardSettings::show,
            )
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, line: LineView, c: ChatColors, busy: Boolean, onClick: () -> Unit, onSettings: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = c.meta, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(line.text, style = chatSecondaryStyle(c.ink), modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        if (line.button.isNotEmpty()) {
            TextButton(onClick = onClick, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(line.button, color = c.accent) }
        }
        if (onSettings != null) {
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Tune, contentDescription = "Card settings", tint = c.accent)
            }
        }
    }
}

/** The row under the chat-name filter: `Search messages for "text"`. */
@Composable
internal fun SearchMessagesRow(text: String, c: ChatColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text("Search messages for “$text”", style = chatSecondaryStyle(c.accent, FontWeight.SemiBold), maxLines = 1)
    }
}
