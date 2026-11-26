package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The full-screen Search view inside the WhatsApp tab: one compact header (back, the query field, help), the
 * Conversations | Messages | Tags switch, one controls row (Filters pill, the selected tags, the status chip)
 * and the results. Filters and the index / topic-card status open in sheets. All state lives in
 * [WhatsAppSearch], so it survives opening a hit and coming back.
 */
@Composable
internal fun SearchScreen(vm: WhatsAppViewModel, onOpenCardSettings: () -> Unit = {}) {
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
    var filtersOpen by remember { mutableStateOf(false) }
    var statusOpen by remember { mutableStateOf(false) }
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
            HelpButton(c) { s.openGuide() }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.inBg)
        vm.message?.let { Toast(it) }
        if (s.modes.isNotEmpty()) ModeSwitch(s, c)
        SearchControls(s, c, onFilters = { filtersOpen = true }, onStatus = { statusOpen = true })
        when (s.mode) {
            SearchMode.CONVERSATIONS -> ConversationResults(vm, c, Modifier.weight(1f))
            SearchMode.MESSAGES -> MessageResults(vm, c, Modifier.weight(1f))
            SearchMode.TAGS -> TagsResults(vm, c, Modifier.weight(1f))
        }
    }
    if (filtersOpen) SearchFiltersSheet(s, c) { filtersOpen = false }
    if (statusOpen) SearchStatusSheet(s, c, onOpenCardSettings) { statusOpen = false }
    if (s.guideOpen) SearchHelpSheet(s.mode, c, onExample = s::openFor, onDismiss = s::closeGuide)
}

/** A rounded toggle for the filter chips: a 48 dp touch target around a 32 dp pill; accent fill when on. */
@Composable
internal fun SearchPill(label: String, on: Boolean, c: ChatColors, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, semanticsOverride: (SemanticsPropertyReceiver.() -> Unit)? = null) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .semantics(properties = semanticsOverride ?: { role = Role.Button; selected = on })
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
