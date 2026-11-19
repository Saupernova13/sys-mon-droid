package com.raavivi.sysmon.ui.whatsapp

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.raavivi.sysmon.ui.theme.SysMon
import com.raavivi.sysmon.core.model.WaStatus
import com.raavivi.sysmon.ui.common.LoadingBox
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WhatsAppScreen(onBack: (() -> Unit)? = null) {
    val vm = rememberContainerViewModel { WhatsAppViewModel.from(it) }
    vm.message?.let { msg -> LaunchedEffect(msg) { delay(2500); vm.message = null } }

    // Leaving the screen (tab switch) must not leave the microphone open.
    DisposableEffect(vm) { onDispose { vm.recorder.cancel() } }

    // System back from a conversation returns to the chat list, not out of the tab.
    BackHandler(enabled = vm.currentJid != null) { vm.closeChat() }

    if (vm.currentJid == null) {
        ChatListView(vm, onBack)
    } else {
        ConversationView(vm)
    }
}

@Composable
private fun ChatListView(vm: WhatsAppViewModel, onBack: (() -> Unit)?) {
    var filter by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var menuJid by remember { mutableStateOf<String?>(null) }
    val c = rememberChatColors()
    val reduced = rememberReducedMotion()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "WhatsApp", actions = {
            StatusDot(vm.status)
            IconButton(onClick = { showNew = true }) { Icon(Icons.Filled.Add, contentDescription = "New chat") }
            IconButton(onClick = { vm.loadChats(); vm.loadStatus() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            onBack?.let { back ->
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            }
        })

        StatusBanner(vm.status, onRetry = { vm.loadStatus(); vm.loadChats() })
        vm.message?.let { Toast(it) }

        ChatSearchField(filter, { filter = it }, c, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

        Box(Modifier.fillMaxSize()) {
            when {
                vm.loadingChats && vm.chats.isEmpty() -> LoadingBox()
                vm.chats.isEmpty() -> Empty(vm.error ?: "No chats")
                else -> {
                    val shown = vm.chats.filter { waChatMatches(it, filter) }
                    val nowSec = remember(vm.chats) { System.currentTimeMillis() / 1000.0 }
                    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(shown, key = { it.jid }) { chat ->
                            ChatListRow(
                                chat = chat,
                                nowSec = nowSec,
                                avatar = { Avatar(chat.name, chat.jid, vm, size = 48.dp) },
                                c = c,
                                reduced = reduced,
                                active = false,
                                onClick = { vm.openChat(chat.jid, chat.name) },
                                menuOpen = menuJid == chat.jid,
                                onMenuOpenChange = { open -> menuJid = if (open) chat.jid else null },
                                menu = {
                                    ChatRowMenuItems(
                                        chat = chat,
                                        c = c,
                                        onTogglePin = { vm.togglePinFor(chat); menuJid = null },
                                        onToggleLocalPin = { vm.localPin(chat, !chat.localPin); menuJid = null },
                                        onMarkRead = { vm.markReadChat(chat.jid); menuJid = null },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNew) NewChatDialog(vm, onDismiss = { showNew = false })
}

@Composable
internal fun MessageInput(
    vm: WhatsAppViewModel,
    text: String,
    onTextChange: (String) -> Unit,
    chip: ComposerChip?,
    onChipCancel: () -> Unit,
    onSend: (String) -> Unit,
    onAttach: (Uri, String) -> Unit,
    displayName: (Uri) -> String,
    enabled: Boolean,
    c: ChatColors,
    reduced: Boolean,
) {
    var picked by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) picked = uri
    }
    val context = LocalContext.current
    val recorder = vm.recorder
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recorder.start() else vm.message = "Microphone permission denied"
    }
    val onMic: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) recorder.start() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    if (recorder.recording) {
        ChatRecordingBar(
            elapsed = formatClock(recorder.elapsedMs),
            c = c,
            reduced = reduced,
            danger = MaterialTheme.colorScheme.error,
            onCancel = { recorder.cancel() },
            onSend = { vm.sendRecording() },
        )
    } else {
        ChatComposer(
            text = text,
            onTextChange = onTextChange,
            enabled = enabled,
            c = c,
            reduced = reduced,
            onSend = { if (text.isNotBlank()) onSend(text) },
            onAttach = { picker.launch("*/*") },
            onMic = onMic,
            chip = chip,
            onChipCancel = onChipCancel,
        )
    }

    picked?.let { uri ->
        AttachDialog(
            name = remember(uri) { displayName(uri) },
            onSend = { caption -> onAttach(uri, caption); picked = null },
            onDismiss = { picked = null },
        )
    }
}

@Composable
private fun AttachDialog(name: String, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var caption by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send file") },
        text = {
            Column {
                Text(name, fontWeight = FontWeight.Medium)
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    placeholder = { Text("Caption (optional)") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSend(caption) }) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NewChatDialog(vm: WhatsAppViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    LaunchedEffect(query) { delay(350); vm.searchContacts(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("New chat") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search contacts…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(vm.contacts, key = { it.jid }) { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.openChat(c.jid, c.name); onDismiss() }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(c.name, c.jid, vm)
                            Spacer(Modifier.size(10.dp))
                            Text(c.name, maxLines = 1)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun StatusBanner(status: WaStatus?, onRetry: () -> Unit) {
    val s = status ?: return
    val note = when {
        !s.enabled -> "WhatsApp widget is disabled on the server (SYS_MON_WHATSAPP=0)."
        !s.binaryFound -> "wacli not found on the host — install it to enable WhatsApp."
        !s.authenticated -> "Not paired. Run `wacli auth` in a terminal on the host to link a device."
        !s.daemonRunning -> "Paired, but the live sync daemon isn't running yet."
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(note, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                // The server's reason when it could not tell (e.g. "sync daemon exited (code 1): ...").
                if (!s.authenticated) {
                    s.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            TextButton(onClick = onRetry) {
                Text("Retry", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun StatusDot(status: WaStatus?) {
    val color = when {
        status == null -> MaterialTheme.colorScheme.tertiary
        status.authenticated && status.daemonRunning -> SysMon.palette.sigOk
        status.authenticated -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
    Spacer(Modifier.size(8.dp))
}


/** The chat's picture over tinted initials; see [ChatAvatar]. */
@Composable
internal fun Avatar(name: String, jid: String, vm: WhatsAppViewModel, size: Dp = 40.dp) {
    ChatAvatar(
        name = name,
        jid = jid,
        url = vm.avatarUrl(jid),
        version = vm.avatarVersions[jid] ?: 0,
        imageLoader = vm.imageLoader,
        c = rememberChatColors(),
        size = size,
    )
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Toast(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}
