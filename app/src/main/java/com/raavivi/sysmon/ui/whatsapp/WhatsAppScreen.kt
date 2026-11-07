package com.raavivi.sysmon.ui.whatsapp

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.raavivi.sysmon.ui.theme.SysMon
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaStatus
import com.raavivi.sysmon.core.model.waInitials
import com.raavivi.sysmon.ui.common.LoadingBox
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.formatTime
import com.raavivi.sysmon.ui.common.relativeTime
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import kotlinx.coroutines.delay

@Composable
fun WhatsAppScreen(onBack: (() -> Unit)? = null) {
    val vm = rememberContainerViewModel { WhatsAppViewModel(it) }
    vm.message?.let { msg -> LaunchedEffect(msg) { delay(2500); vm.message = null } }

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

        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            placeholder = { Text("Filter chats…") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )

        Box(Modifier.fillMaxSize()) {
            when {
                vm.loadingChats && vm.chats.isEmpty() -> LoadingBox()
                vm.chats.isEmpty() -> Empty(vm.error ?: "No chats")
                else -> {
                    val shown = vm.chats.filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
                    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(shown, key = { it.jid }) { chat ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.openChat(chat.jid, chat.name) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(chat.name, chat.jid, vm)
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (chat.pinned) {
                                            Icon(Icons.Filled.PushPin, contentDescription = "Pinned",
                                                modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.size(4.dp))
                                        }
                                        Text(chat.name, maxLines = 1, fontWeight = FontWeight.Medium)
                                    }
                                    if (chat.lastTs > 0) {
                                        Text(relativeTime(chat.lastTs), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (chat.unread > 0) {
                                    Badge { Text(chat.unread.toString()) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNew) NewChatDialog(vm, onDismiss = { showNew = false })
}

@Composable
private fun ConversationView(vm: WhatsAppViewModel) {
    var viewing by remember { mutableStateOf<WaMessage?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        ScreenHeader(title = vm.currentName, actions = {
            vm.currentJid?.let { Avatar(vm.currentName, it, vm, size = 28.dp) }
            Spacer(Modifier.size(4.dp))
            IconButton(onClick = { vm.markRead() }, enabled = vm.currentUnread > 0) {
                Icon(Icons.Filled.DoneAll, contentDescription = "Mark as read")
            }
            IconButton(onClick = { vm.loadOlder() }) { Icon(Icons.Filled.ExpandLess, contentDescription = "Load older") }
            IconButton(onClick = { vm.togglePin() }) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = "Pin",
                    tint = if (vm.currentPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { vm.closeChat() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })

        vm.message?.let { Toast(it) }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.loadingMessages && vm.messages.isEmpty()) {
                LoadingBox()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(vm.messages, key = { it.id.ifBlank { "${it.ts}-${it.text.hashCode()}" } }) { msg ->
                        MessageBubble(msg, vm, onOpenImage = { viewing = it })
                    }
                }
            }
        }

        vm.uploading?.let { name ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Uploading $name...", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }

        MessageInput(
            onSend = { vm.send(it) },
            onAttach = { uri, caption -> vm.sendMedia(uri, caption) },
            displayName = vm::displayName,
            enabled = !vm.sending,
        )
    }

    viewing?.let { ImageViewer(it, vm, onClose = { viewing = null }) }
}

@Composable
private fun MessageBubble(msg: WaMessage, vm: WhatsAppViewModel, onOpenImage: (WaMessage) -> Unit) {
    val mine = msg.fromMe
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(if (SysMon.palette.isInstrument) 2.dp else 12.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (!mine && msg.senderName.isNotBlank()) {
                    Text(msg.senderName, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
                if (msg.mediaType != null) {
                    if (msg.mediaType.contains("image", true) || msg.mediaType.contains("sticker", true)) {
                        // A failed load (media not downloaded, 502) shows a retry prompt; the
                        // nonce changes the cache keys so the retry really refetches.
                        var failed by remember(msg.id) { mutableStateOf(false) }
                        var nonce by remember(msg.id) { mutableIntStateOf(0) }
                        val url = vm.mediaUrl(msg)
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(url)
                                .memoryCacheKey("$url#$nonce")
                                .diskCacheKey("$url#$nonce")
                                .build(),
                            imageLoader = vm.imageLoader,
                            contentDescription = msg.filename ?: "image",
                            onState = { failed = it is AsyncImagePainter.State.Error },
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(if (SysMon.palette.isInstrument) 2.dp else 8.dp))
                                .clickable { onOpenImage(msg) },
                        )
                        if (failed) {
                            Text(
                                "Tap to load image",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { failed = false; nonce++ }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(msg.filename ?: msg.mediaType, style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
                if (msg.text.isNotBlank()) Text(msg.text)
                Text(
                    formatTime(msg.ts),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun MessageInput(
    onSend: (String) -> Unit,
    onAttach: (Uri, String) -> Unit,
    displayName: (Uri) -> String,
    enabled: Boolean,
) {
    var text by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) picked = uri
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { picker.launch("*/*") }, enabled = enabled) {
            Icon(Icons.Filled.AttachFile, contentDescription = "Attach file")
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message…") },
        )
        Spacer(Modifier.size(6.dp))
        IconButton(onClick = { if (text.isNotBlank()) { onSend(text); text = "" } }, enabled = enabled) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
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

/** Initials on a tinted disc; the cached picture, once it loads, covers them (a 404 leaves the initials). */
@Composable
private fun Avatar(name: String, jid: String, vm: WhatsAppViewModel, size: Dp = 40.dp) {
    val version = vm.avatarVersions[jid] ?: 0
    val key = "avatar:$jid:$version"
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(waInitials(name), color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Medium)
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(vm.avatarUrl(jid))
                .memoryCacheKey(key)
                .diskCacheKey(key)
                .build(),
            imageLoader = vm.imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Toast(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}
