package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The full-screen Search view inside the WhatsApp tab: a query field, the Conversations | Messages toggle
 * (when chat search is on), filter chips (Messages only), the index and topic-card lines (Conversations)
 * and the results. All state lives in [WhatsAppSearch], so it survives opening a hit and coming back.
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
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderAction(Icons.AutoMirrored.Filled.ArrowBack, "Back to chats", c, { s.closeView() })
            ChatSearchField(
                value = s.query,
                onValueChange = s::onQueryChange,
                c = c,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                placeholder = if (s.mode == SearchMode.CONVERSATIONS) "Search conversations" else "Search messages",
                onSearch = s::submit,
                autoFocus = s.query.isEmpty(),
            )
        }
        vm.message?.let { Toast(it) }
        if (s.conv.available) ModeRow(s, c)
        if (s.mode == SearchMode.MESSAGES) ChipRow(s, c)
        StatusLines(s, c)
        when (s.mode) {
            SearchMode.CONVERSATIONS -> ConversationResults(vm, c, Modifier.weight(1f))
            SearchMode.MESSAGES -> MessageResults(vm, c, Modifier.weight(1f))
        }
    }
}

/** A rounded toggle: the filter chips and the mode switch. */
@Composable
internal fun SearchPill(label: String, on: Boolean, c: ChatColors, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(CircleShape)
            .background(if (on) c.accent else c.inBg)
            .semantics { role = Role.Button; selected = on }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = chatSecondaryStyle(if (on) c.onAccent else c.ink, FontWeight.Medium))
    }
}

@Composable
private fun ModeRow(s: WhatsAppSearch, c: ChatColors) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchPill("Conversations", s.mode == SearchMode.CONVERSATIONS, c, { s.setMode(SearchMode.CONVERSATIONS) })
        SearchPill("Messages", s.mode == SearchMode.MESSAGES, c, { s.setMode(SearchMode.MESSAGES) })
    }
}

@Composable
private fun ChipRow(s: WhatsAppSearch, c: ChatColors) {
    val sel = s.messages.chips
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchPill("All", sel.none, c, { s.pressChip(null) })
        for (chip in SearchChip.entries) SearchPill(chip.label, sel.isOn(chip), c, { s.pressChip(chip) })
    }
}

/** The index line and the topic-cards line: both belong to Conversations, or to a view with no such mode yet. */
@Composable
private fun StatusLines(s: WhatsAppSearch, c: ChatColors) {
    val inConv = !s.conv.available || s.mode == SearchMode.CONVERSATIONS
    val status = s.conv.status
    val index = indexView(status, inConv)
    if (index.visible) StatusLine(index, c, busy = s.conv.indexBusy, onClick = s.conv::requestIndex)
    val cards = cardsView(status, inConv)
    if (cards.visible) StatusLine(cards, c, busy = s.conv.cardsBusy, onClick = { s.conv.toggleCards(cards.running) })
}

@Composable
private fun StatusLine(line: LineView, c: ChatColors, busy: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(line.text, style = chatSecondaryStyle(c.meta), modifier = Modifier.weight(1f))
        if (line.button.isNotEmpty()) {
            TextButton(onClick = onClick, enabled = !busy) { Text(line.button, color = c.accent) }
        }
    }
}

/** A centred note in place of results ("Searching...", "No messages found"). */
@Composable
internal fun SearchNote(text: String, c: ChatColors, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = chatSecondaryStyle(c.meta))
    }
}

/** The row under the chat-name filter: `Search messages for "text"`. */
@Composable
internal fun SearchMessagesRow(text: String, c: ChatColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text("Search messages for “$text”", style = chatSecondaryStyle(c.accent, FontWeight.Medium), maxLines = 1)
    }
}
