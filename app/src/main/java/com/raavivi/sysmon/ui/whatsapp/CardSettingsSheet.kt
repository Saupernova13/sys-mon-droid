package com.raavivi.sysmon.ui.whatsapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.WaChat

/**
 * The Card settings sheet, reached from the topic-cards line: the night window, the chats to card (with a
 * picker that shows what adding one would take) and the smallest topic that gets a card. A scrolling body above
 * a pinned bar with the refusal text, Save and Cancel. Everything it edits is a draft until Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardSettingsSheet(vm: WhatsAppViewModel, st: CardSettingsState, c: ChatColors) {
    if (!st.open) return
    ModalBottomSheet(
        onDismissRequest = st::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.wall,
        contentColor = c.ink,
    ) {
        val draft = st.draft
        when {
            st.phase == CardSettingsPhase.LOADING -> Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accent)
            }
            st.phase == CardSettingsPhase.ERROR -> Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Could not load the card settings", style = chatSecondaryStyle(c.ink, FontWeight.SemiBold))
                if (st.loadError.isNotBlank()) Text(st.loadError, style = chatSecondaryStyle(c.meta))
                OutlinedButton(onClick = st::retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry", color = c.accent) }
            }
            draft == null -> Unit
            st.picking -> ChatPicker(vm, st, draft, c)
            else -> SettingsBody(vm, st, draft, c)
        }
    }
}

@Composable
private fun SettingsBody(vm: WhatsAppViewModel, st: CardSettingsState, draft: CardDraft, c: ChatColors) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text("Card settings", style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold))
            NightSection(st, draft, c)
            ChatsSection(vm, st, draft, c)
            MinMsgsSection(st, draft, c)
            Spacer(Modifier.size(8.dp))
        }
        SaveBar(st, c)
    }
}

// -- night window ---------------------------------------------------------------------------------

@Composable
private fun NightSection(st: CardSettingsState, draft: CardDraft, c: ChatColors) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = draft.night, role = Role.Switch, onValueChange = st::setNight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Write cards overnight", style = chatSecondaryStyle(c.ink, FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Switch(
                checked = draft.night,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
            )
        }
        if (draft.night) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField("From", draft.from, c, Modifier.weight(1f), st::setFrom)
                TimeField("To", draft.to, c, Modifier.weight(1f), st::setTo)
            }
        }
        Text(nightHint(draft), style = chatMetaStyle(c.meta))
    }
}

/** A tappable time (label, then the time as the value); a tap opens the 24-hour picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(label: String, minutes: Int, c: ChatColors, modifier: Modifier, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.inBg)
            .clickable { open = true }
            .semantics { role = Role.Button; contentDescription = "$label ${clockText(minutes)}" }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = chatMetaStyle(c.meta))
        Text(clockText(minutes), style = chatBodyStyle(c.accent).copy(fontWeight = FontWeight.SemiBold))
    }
    if (open) {
        val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onPick(state.hour * 60 + state.minute)
                    open = false
                }) { Text("OK", color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel", color = c.accent) } },
            text = { TimePicker(state) },
        )
    }
}

// -- chats ----------------------------------------------------------------------------------------

@Composable
private fun ChatsSection(vm: WhatsAppViewModel, st: CardSettingsState, draft: CardDraft, c: ChatColors) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Chats to card", style = chatSecondaryStyle(c.ink, FontWeight.SemiBold))
        if (draft.chats.isEmpty()) {
            Text("No chats are carded. Add one to start.", style = chatSecondaryStyle(c.meta))
        }
        for (chat in draft.chats) {
            val name = vm.searchChatName(chat.jid, chat.name)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = chatBodyStyle(c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(chatProgress(chat), style = chatSecondaryStyle(c.meta))
                }
                IconButton(onClick = { st.removeChat(chat.jid) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove $name", tint = c.meta)
                }
            }
        }
        OutlinedButton(
            onClick = st::openPicker,
            enabled = draft.chats.size < CARD_MAX_CHATS,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add chat", color = c.accent)
        }
    }
}

/** The full-height chat picker: a search field over the chat list, then the confirm card for the tapped chat. */
@Composable
private fun ChatPicker(vm: WhatsAppViewModel, st: CardSettingsState, draft: CardDraft, c: ChatColors) {
    BackHandler { if (st.probe != null) st.clearProbe() else st.closePicker() }
    var query by remember { mutableStateOf("") }
    val listed = remember(draft.chats) { draft.chats.map { it.jid }.toSet() }
    val shown = remember(vm.chats, query) { pickerChats(vm.chats, query) }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = st::closePicker, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the card settings", tint = c.meta)
            }
            Text("Add chat", style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold))
        }
        val probe = st.probe
        if (probe != null) {
            ProbeCard(st, probe, c)
            return@Column
        }
        ChatSearchField(query, { query = it }, c, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), placeholder = "Search chats")
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.jid }) { chat -> PickerRow(vm, chat, chat.jid in listed, c) { st.pick(chat.jid, chat.name) } }
        }
    }
}

@Composable
private fun PickerRow(vm: WhatsAppViewModel, chat: WaChat, listed: Boolean, c: ChatColors, onPick: () -> Unit) {
    val ink = if (listed) c.meta else c.ink
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (listed) Modifier else Modifier.clickable(onClick = onPick))
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(chat.name, chat.jid, vm, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(chat.name, style = chatBodyStyle(ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (listed) Text("Carded", style = chatSecondaryStyle(c.meta))
    }
}

/** What carding the tapped chat would take, with Add and Back; a failed read offers Back only. */
@Composable
private fun ProbeCard(st: CardSettingsState, probe: AddProbe, c: ChatColors) {
    val chat = probe.chat
    val name = probe.name.ifBlank { probe.jid }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            probe.failed -> Text("Could not read $name. Go back and try again.", style = chatBodyStyle(c.ink))
            chat == null -> Box(Modifier.fillMaxWidth().heightIn(min = 96.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accent)
            }
            else -> Text(addEstimate(name, chat), style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = st::clearProbe, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back", color = c.accent) }
            Button(
                onClick = st::confirmAdd,
                enabled = chat != null,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
            ) { Text("Add", style = chatSecondaryStyle(c.onAccent, FontWeight.SemiBold)) }
        }
    }
}

// -- minimum messages -----------------------------------------------------------------------------

@Composable
private fun MinMsgsSection(st: CardSettingsState, draft: CardDraft, c: ChatColors) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Minimum messages per topic", style = chatSecondaryStyle(c.ink, FontWeight.SemiBold))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { st.stepMin(-1) },
                enabled = draft.minMsgs > CARD_MIN_MSGS_LOW,
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Filled.Remove, contentDescription = "Fewer messages", tint = c.accent) }
            Text(
                "${draft.minMsgs}",
                style = chatBodyStyle(c.ink).copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.width(72.dp).padding(horizontal = 8.dp),
            )
            IconButton(
                onClick = { st.stepMin(1) },
                enabled = draft.minMsgs < CARD_MIN_MSGS_HIGH,
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Filled.Add, contentDescription = "More messages", tint = c.accent) }
        }
        Text("Topics with fewer messages than this get no card.", style = chatMetaStyle(c.meta))
    }
}

// -- save bar -------------------------------------------------------------------------------------

@Composable
private fun SaveBar(st: CardSettingsState, c: ChatColors) {
    Column(
        Modifier.fillMaxWidth().background(c.inBg).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        st.problem?.let { Text(it, style = chatSecondaryStyle(MaterialTheme.colorScheme.error), modifier = Modifier.padding(horizontal = 8.dp)) }
        Button(
            onClick = st::save,
            enabled = st.canSave,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
        ) {
            if (st.saving) {
                CircularProgressIndicator(Modifier.size(20.dp), color = c.onAccent, strokeWidth = 2.dp)
            } else {
                Text("Save", style = chatSecondaryStyle(c.onAccent, FontWeight.SemiBold))
            }
        }
        TextButton(onClick = st::close, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
            Text("Cancel", color = c.accent)
        }
    }
}
