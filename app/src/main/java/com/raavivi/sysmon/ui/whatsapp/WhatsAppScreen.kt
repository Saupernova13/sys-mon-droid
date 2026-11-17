package com.raavivi.sysmon.ui.whatsapp

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.snapshotFlow
import kotlin.math.roundToInt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.flow.first

@Composable
fun WhatsAppScreen(onBack: (() -> Unit)? = null) {
    val vm = rememberContainerViewModel { WhatsAppViewModel(it) }
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
    val listState = rememberLazyListState()

    // Open (and send / jump) at the newest message.
    LaunchedEffect(vm.scrollTick) {
        val last = vm.messages.lastIndex
        if (last >= 0) listState.scrollToItem(last, Int.MAX_VALUE) // offset clamps to the true end, so a tall last message is fully shown
    }
    // Jump to first: the replaced window starts at its first item.
    LaunchedEffect(vm.topTick) {
        if (vm.topTick > 0 && vm.messages.isNotEmpty()) listState.scrollToItem(0)
    }
    // A live message follows the bottom only if the user was already there (or sent it). Gated on
    // liveTick so appending a forward page or merging the newest page never yanks the list down.
    var seenCount by remember { mutableIntStateOf(vm.messages.size) }
    var seenNewest by remember { mutableStateOf(vm.messages.lastOrNull()?.id) }
    var seenLive by remember { mutableIntStateOf(vm.liveTick) }
    val newestId = vm.messages.lastOrNull()?.id
    LaunchedEffect(newestId, vm.messages.size, vm.liveTick) {
        val count = vm.messages.size
        val live = vm.liveTick != seenLive
        seenLive = vm.liveTick
        if (live && newestId != seenNewest) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearBottom = lastVisible >= seenCount - 2
            if (count > seenCount && (nearBottom || vm.messages.lastOrNull()?.fromMe == true)) {
                listState.animateScrollToItem(count - 1)
            }
        }
        seenCount = count
        seenNewest = newestId
    }
    // Scrolling near the end of a window that stops short of the newest message pages forward.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= 0 && last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, vm.atLatest, vm.messages.size) {
        if (nearEnd && !vm.atLatest) vm.loadNewer()
    }
    // Reading position on the scrubber: the day of the message at the top of the list.
    val readingPos by remember {
        derivedStateOf {
            val tl = vm.timeline
            val ts = vm.messages.getOrNull(listState.firstVisibleItemIndex)?.ts
            if (tl == null || ts == null) 0.0 else posOfTs(tl, ts.toLong())
        }
    }
    // Reaching the top pages older messages in on its own, but only after the user has scrolled:
    // the initial open-at-bottom (and a jump) must not trigger it.
    var userScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress }.first { it }
        userScrolled = true
    }
    LaunchedEffect(userScrolled) {
        if (!userScrolled) return@LaunchedEffect
        snapshotFlow { (listState.firstVisibleItemIndex <= 2) to vm.messages.size }
            .collect { (atTop, size) -> if (atTop && size > 0) vm.loadOlder(silent = true) }
    }
    var showDatePicker by remember { mutableStateOf(false) }
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
            IconButton(onClick = { showDatePicker = true }, enabled = vm.timeline != null) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = "Go to date")
            }
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
                // Stable keys make the list keep its position when older messages are prepended.
                LazyColumn(
                    state = listState,
                    // Leave room on the right for the scrubber track.
                    contentPadding = PaddingValues(
                        start = 8.dp, end = if (vm.timeline != null) 36.dp else 8.dp, top = 8.dp, bottom = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(vm.messages, key = { it.id.ifBlank { "${it.ts}-${it.text.hashCode()}" } }) { msg ->
                        MessageBubble(msg, vm, onOpenImage = { viewing = it })
                    }
                }
            }
            vm.timeline?.let { tl ->
                // Ends above the FAB column below so those stay reachable.
                TimelineScrubber(
                    tl = tl,
                    readingPos = readingPos,
                    busy = vm.jumpingDate,
                    onJump = { vm.jumpToTs(it) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, bottom = 116.dp),
                )
            }
            Column(
                Modifier.align(Alignment.BottomEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallFloatingActionButton(onClick = { vm.jumpToFirst() }) {
                    if (vm.jumpingFirst) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.VerticalAlignTop, contentDescription = "Jump to first message")
                    }
                }
                SmallFloatingActionButton(
                    onClick = { vm.jumpToLatest() },
                    containerColor = if (vm.atLatest) FloatingActionButtonDefaults.containerColor
                    else MaterialTheme.colorScheme.primaryContainer,
                ) {
                    if (vm.jumping) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Jump to latest")
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
                Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }

        MessageInput(
            vm = vm,
            onSend = { vm.send(it) },
            onAttach = { uri, caption -> vm.sendMedia(uri, caption) },
            displayName = vm::displayName,
            enabled = !vm.sending,
        )
    }

    viewing?.let { ImageViewer(it, vm, onClose = { viewing = null }) }

    val tl = vm.timeline
    if (showDatePicker && tl != null) {
        GoToDateDialog(
            tl = tl,
            initialDay = dayAt(tl, readingPos).day,
            onPick = { vm.jumpToTs(it); showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }
}

/**
 * Google-Photos-style scrubber: year labels, a thumb following the reading position, and a date
 * bubble while dragging. A drag (or tap) jumps to the chosen day on release; the thumb ignores
 * scroll while the finger is down. The root is wider than the track so the bubble fits to its left;
 * only the track itself takes touches.
 */
@Composable
private fun TimelineScrubber(
    tl: WaTimeline,
    readingPos: Double,
    busy: Boolean,
    onJump: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var heightPx by remember { mutableIntStateOf(0) }
    var dragFrac by remember { mutableStateOf<Double?>(null) }
    val frac = dragFrac ?: readingPos
    val seg = dayAt(tl, frac)
    val currentOnJump by rememberUpdatedState(onJump)

    // Year labels at their track position, dropping any too close to the previous one.
    val yearLabels = remember(tl, heightPx) {
        val minGap = with(density) { 14.dp.toPx() }
        var lastY = -minGap
        tl.years.mapNotNull { y ->
            val py = (y.pos * heightPx).toFloat()
            if (py - lastY < minGap) null else { lastY = py; y.year to py }
        }
    }
    val thumbH = 6.dp
    val thumbHPx = with(density) { thumbH.toPx() }
    val thumbY = (frac * heightPx).toFloat().coerceIn(0f, (heightPx - thumbHPx).coerceAtLeast(0f))

    Box(modifier.width(160.dp).fillMaxHeight().onSizeChanged { heightPx = it.height }) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .width(28.dp)
                .fillMaxHeight()
                .semantics {
                    contentDescription = "Timeline"
                    stateDescription = fmtDay(seg.day)
                }
                .pointerInput(tl) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun fracOf(y: Float): Double =
                            if (size.height > 0) (y / size.height).toDouble() else 0.0
                        dragFrac = fracOf(down.position.y)
                        down.consume()
                        val completed = drag(down.id) { change ->
                            dragFrac = fracOf(change.position.y)
                            change.consume()
                        }
                        val end = dragFrac
                        dragFrac = null
                        if (completed && end != null) currentOnJump(dayAt(tl, end).firstTs)
                    }
                },
        ) {
            // Rail and thumb hug the right edge so the year labels to their left stay clear.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 6.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            for ((year, py) in yearLabels) {
                Text(
                    year,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    maxLines = 1,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset { IntOffset(0, py.roundToInt()) },
                )
            }
            if (busy) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.TopEnd).offset { IntOffset(0, thumbY.roundToInt()) }.size(14.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, thumbY.roundToInt()) }
                        .width(14.dp)
                        .height(thumbH)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        if (dragFrac != null) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(if (SysMon.palette.isInstrument) 2.dp else 8.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 32.dp)
                    .offset { IntOffset(0, (thumbY - with(density) { 8.dp.toPx() }).coerceAtLeast(0f).roundToInt()) },
            ) {
                Text(
                    fmtDay(seg.day),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Date picker limited to days that have messages; picking one jumps to that day's first message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoToDateDialog(tl: WaTimeline, initialDay: String, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val present = remember(tl) { tl.segments.mapTo(HashSet()) { it.day } }
    val selectable = remember(tl) {
        object : SelectableDates {
            // Picker millis are UTC midnight, so the UTC date is the calendar day the user tapped.
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().toString() in present

            override fun isSelectableYear(year: Int): Boolean =
                year in tl.first.day.take(4).toInt()..tl.last.day.take(4).toInt()
        }
    }
    val initialMillis = remember(initialDay) {
        runCatching { LocalDate.parse(initialDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialMillis,
        initialDisplayedMonthMillis = initialMillis,
        yearRange = tl.first.day.take(4).toInt()..tl.last.day.take(4).toInt(),
        selectableDates = selectable,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val ymd = state.selectedDateMillis
                        ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    val target = ymd?.let { firstDayOnOrAfter(tl, it) }
                    if (target != null) onPick(target.firstTs) else onDismiss()
                },
                enabled = state.selectedDateMillis != null,
            ) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
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
                        // Old media not on the PC waits for a tap: its download almost always fails and
                        // each attempt bounces the live sync.
                        var load by remember(msg.id, msg.downloaded) {
                            mutableStateOf(shouldAutoLoadImage(msg.downloaded, msg.ts, System.currentTimeMillis() / 1000.0))
                        }
                        val url = vm.mediaUrl(msg)
                        if (!load) {
                            Text(
                                "Tap to load image",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { load = true }
                                    .padding(vertical = 8.dp),
                            )
                        } else AsyncImage(
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
                        if (load && failed) {
                            Text(
                                "Tap to load image",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { failed = false; nonce++ }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    } else if (isAudioMedia(msg.mediaType)) {
                        VoiceNoteRow(msg, vm)
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

/** Compact voice-note player: play/pause, seek, elapsed/total and a 1x/1.5x/2x speed toggle. */
@Composable
private fun VoiceNoteRow(msg: WaMessage, vm: WhatsAppViewModel) {
    val v = vm.voice
    val current = v.currentId == msg.id
    val loading = v.loadingId == msg.id
    val playing = current && v.isPlaying
    val duration = if (current) v.durationMs else 0
    val position = if (current) v.positionMs else 0
    var dragging by remember(msg.id) { mutableStateOf<Float?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(min = 220.dp)) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { v.toggle(msg.id, vm.mediaUrl(msg)) }) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause voice note" else "Play voice note",
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Slider(
                value = dragging ?: if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { v.seekTo((it * duration).toInt()) }
                    dragging = null
                },
                enabled = current && duration > 0,
            )
            Text(
                "${formatClock(position.toLong())} / ${if (current) formatClock(duration.toLong()) else "--:--"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        TextButton(onClick = { v.cycleSpeed() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(speedLabel(v.speed))
        }
    }
}

@Composable
private fun MessageInput(
    vm: WhatsAppViewModel,
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
        RecordingBar(recorder, onCancel = { recorder.cancel() }, onSend = { vm.sendRecording() })
    } else Row(
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
        IconButton(onClick = onMic, enabled = enabled) {
            Icon(Icons.Filled.Mic, contentDescription = "Record voice note")
        }
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

/** Replaces the input row while recording: red dot, elapsed timer, cancel and send. */
@Composable
private fun RecordingBar(recorder: VoiceRecorder, onCancel: () -> Unit, onSend: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Delete, contentDescription = "Cancel recording")
        }
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
        )
        Spacer(Modifier.size(8.dp))
        Text(formatClock(recorder.elapsedMs), modifier = Modifier.weight(1f))
        IconButton(onClick = onSend) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice note")
        }
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
