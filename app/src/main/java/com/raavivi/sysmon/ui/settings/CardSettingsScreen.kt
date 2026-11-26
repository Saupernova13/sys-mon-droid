package com.raavivi.sysmon.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.SectionCard
import com.raavivi.sysmon.ui.common.SettingsRow
import com.raavivi.sysmon.ui.common.SwitchRow
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import kotlinx.coroutines.withTimeoutOrNull

/** How long "Removed · Undo" waits. */
private const val UNDO_MS = 5_000L

/**
 * Settings > Topic cards (admin): the night window, the chats to card and the smallest topic that gets a card.
 * Everything is a draft until Save; Back with unsaved changes asks first. The chat picker is a screen state of
 * this route, so system Back steps out of it before leaving.
 */
@Composable
fun CardSettingsScreen(onBack: () -> Unit) {
    val vm = rememberContainerViewModel { CardSettingsViewModel(it) }
    val st = vm.state
    val snackbar = remember { SnackbarHostState() }
    var confirmDiscard by remember { mutableStateOf(false) }

    vm.message?.let { msg ->
        LaunchedEffect(msg) {
            snackbar.showSnackbar(msg)
            vm.message = null
        }
    }

    // A removed theme shows "Removed · Undo", as the card editor does.
    LaunchedEffect(st.removalSeq) {
        if (st.removalSeq == 0) return@LaunchedEffect
        val r = withTimeoutOrNull(UNDO_MS) {
            snackbar.showSnackbar("Removed", actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
        }
        if (r == SnackbarResult.ActionPerformed) st.undoRemoveTheme()
    }

    val leave = { if (st.dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = st.picking) { if (st.probe != null) st.clearProbe() else st.closePicker() }
    BackHandler(enabled = !st.picking && st.dirty) { confirmDiscard = true }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (st.picking) {
            ChatPicker(st, Modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                ScreenHeader(title = "Topic cards", actions = {
                    IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                })
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val draft = st.draft
                    when {
                        st.phase == CardSettingsPhase.LOADING -> Box(
                            Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                        st.phase == CardSettingsPhase.OFF -> SectionCard(title = "Chat search is off") {
                            Text(
                                "Turn on WhatsApp chat search to edit topic cards.",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        st.phase == CardSettingsPhase.ERROR -> SectionCard(title = "Could not load the card settings") {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (st.loadError.isNotBlank()) {
                                    Text(st.loadError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                OutlinedButton(onClick = st::retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
                            }
                        }
                        draft != null -> {
                            NightSection(st, draft)
                            ChatsSection(st, draft)
                            TopicSizeSection(st, draft)
                            if (draft.hasThemes) ThemesSection(st, draft)
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.CenterHorizontally))
        if (!st.picking && st.draft != null && st.phase == CardSettingsPhase.READY) SaveBar(st)
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to the topic cards are not saved.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

// -- night window ---------------------------------------------------------------------------------

@Composable
private fun NightSection(st: CardSettingsState, draft: CardDraft) {
    SectionCard(title = "Night window", flush = true) {
        SwitchRow(
            title = "Write cards each night",
            subtitle = nightHint(draft),
            checked = draft.night,
            onCheckedChange = st::setNight,
        )
        if (draft.night) {
            TimeRow("From", draft.from, st::setFrom)
            TimeRow("To", draft.to, st::setTo)
        }
    }
}

/** A row with a label and its time as the emphasised value; a tap opens the 24-hour picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRow(label: String, minutes: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsRow(
        title = label,
        onClick = { open = true },
        trailing = {
            Text(
                clockText(minutes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        },
    )
    if (open) {
        val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onPick(state.hour * 60 + state.minute)
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

// -- chats ----------------------------------------------------------------------------------------

@Composable
private fun ChatsSection(st: CardSettingsState, draft: CardDraft) {
    SectionCard(title = "Chats to card", flush = true) {
        if (draft.chats.isEmpty()) {
            Text(
                "No chats are carded. Add one to start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        for (chat in draft.chats) {
            val name = chat.name.ifBlank { chat.jid }
            SettingsRow(
                title = name,
                subtitle = chatProgress(chat),
                trailing = {
                    IconButton(onClick = { st.removeChat(chat.jid) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove $name", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
        val full = draft.chats.size >= CARD_MAX_CHATS
        SettingsRow(
            icon = Icons.Filled.Add,
            title = "Add chat",
            subtitle = if (full) "At most $CARD_MAX_CHATS chats" else null,
            enabled = !full,
            onClick = st::openPicker,
        )
    }
}

// -- topic size -----------------------------------------------------------------------------------

@Composable
private fun TopicSizeSection(st: CardSettingsState, draft: CardDraft) {
    SectionCard(title = "Topic size", flush = true) {
        SettingsRow(
            title = "Minimum messages",
            subtitle = "A topic with fewer messages gets no card.",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { st.stepMin(-1) },
                        enabled = draft.minMsgs > CARD_MIN_MSGS_LOW,
                        modifier = Modifier.size(48.dp),
                    ) { Icon(Icons.Filled.Remove, contentDescription = "Fewer messages") }
                    Text(
                        "${draft.minMsgs}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.widthIn(min = 40.dp),
                    )
                    IconButton(
                        onClick = { st.stepMin(1) },
                        enabled = draft.minMsgs < CARD_MIN_MSGS_HIGH,
                        modifier = Modifier.size(48.dp),
                    ) { Icon(Icons.Filled.Add, contentDescription = "More messages") }
                }
            },
        )
    }
}

// -- save bar -------------------------------------------------------------------------------------

@Composable
private fun SaveBar(st: CardSettingsState) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            st.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = st::save,
                enabled = st.canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (st.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Save")
            }
        }
    }
}

// -- chat picker ----------------------------------------------------------------------------------

/** The full-screen picker: a search field over the unlisted chats, then the confirm card for the tapped chat. */
@Composable
private fun ChatPicker(st: CardSettingsState, modifier: Modifier) {
    var query by remember { mutableStateOf("") }
    val listed = st.draft?.chats?.map { it.jid }?.toSet().orEmpty()
    val shown = remember(st.chats, query, listed) { pickerChats(st.chats, query, listed) }
    Column(modifier) {
        ScreenHeader(title = "Add chat", actions = {
            IconButton(onClick = { if (st.probe != null) st.clearProbe() else st.closePicker() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the topic cards")
            }
        })
        val probe = st.probe
        if (probe != null) {
            ProbeCard(st, probe)
            return@Column
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Name or phone number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when (st.chatsPhase) {
            PickerChatsPhase.IDLE, PickerChatsPhase.LOADING -> Box(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            PickerChatsPhase.ERROR -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Could not load your chats.", style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = st::loadChats, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
            }
            PickerChatsPhase.READY -> if (shown.isEmpty()) {
                Text(
                    "No chats match.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.jid }) { chat ->
                        SettingsRow(
                            title = chat.name.ifBlank { chat.jid },
                            onClick = { st.pick(chat.jid, chat.name) },
                        )
                    }
                }
            }
        }
    }
}

/** What carding the tapped chat would take, with Add and Back; a failed read offers Back only. */
@Composable
private fun ProbeCard(st: CardSettingsState, probe: AddProbe) {
    val chat = probe.chat
    val name = probe.name.ifBlank { probe.jid }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            probe.failed -> Text("Could not read $name. Go back and try again.", style = MaterialTheme.typography.bodyLarge)
            chat == null -> Box(Modifier.fillMaxWidth().heightIn(min = 96.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> Text(addEstimate(name, chat), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = st::clearProbe, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
            Button(onClick = st::confirmAdd, enabled = chat != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add") }
        }
    }
}
