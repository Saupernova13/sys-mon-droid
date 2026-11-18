package com.raavivi.sysmon.ui.whatsapp

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.raavivi.sysmon.ui.theme.SysMon
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaStatus
import com.raavivi.sysmon.ui.common.LoadingBox
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
                    val shown = vm.chats.filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
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
private fun ConversationView(vm: WhatsAppViewModel) {
    var viewing by remember { mutableStateOf<WaMessage?>(null) }
    val listState = rememberLazyListState()
    val c = rememberChatColors()
    val reduced = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val group = waIsGroup(vm.currentJid)

    // The list's rows (day headers + messages); every index below is a row index, not a message index.
    val rows = remember(vm.messages) { buildRows(vm.messages) }
    val nowSec = remember(vm.messages) { System.currentTimeMillis() / 1000.0 }
    val byId = remember(vm.messages) { vm.messages.filter { it.id.isNotBlank() }.associateBy { it.id } }
    val names = remember(vm.messages) { waNameBySender(vm.messages) }
    var flashId by remember { mutableStateOf<String?>(null) }
    // Chips pop when they first appear or change, but not while the conversation is just opening.
    var popEnabled by remember(vm.currentJid) { mutableStateOf(false) }
    LaunchedEffect(vm.currentJid, rows.isNotEmpty()) { if (rows.isNotEmpty()) { delay(700); popEnabled = true } }
    val reactionSeen = remember(vm.currentJid) { HashMap<String, String>() }
    // The entrance animation is for live / sent messages only; forget them once it has played.
    LaunchedEffect(vm.freshIds) { if (vm.freshIds.isNotEmpty()) { delay(1500); vm.clearFresh() } }

    // Open (and send / jump) at the newest message.
    LaunchedEffect(vm.scrollTick) {
        val last = rows.lastIndex
        if (last >= 0) listState.scrollToItem(last, Int.MAX_VALUE) // offset clamps to the true end, so a tall last message is fully shown
    }
    // Jump to first: the replaced window starts at its first item.
    LaunchedEffect(vm.topTick) {
        if (vm.topTick > 0 && rows.isNotEmpty()) listState.scrollToItem(0)
    }
    // A live message follows the bottom only if the user was already there (or sent it). Gated on
    // liveTick so appending a forward page or merging the newest page never yanks the list down.
    var seenCount by remember { mutableIntStateOf(rows.size) }
    var seenNewest by remember { mutableStateOf(vm.messages.lastOrNull()?.id) }
    var seenLive by remember { mutableIntStateOf(vm.liveTick) }
    var unseen by remember(vm.currentJid) { mutableIntStateOf(0) }
    val newestId = vm.messages.lastOrNull()?.id
    LaunchedEffect(newestId, rows.size, vm.liveTick) {
        val count = rows.size
        val live = vm.liveTick != seenLive
        seenLive = vm.liveTick
        if (live && newestId != seenNewest) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearBottom = lastVisible >= seenCount - 2
            val mine = vm.messages.lastOrNull()?.fromMe == true
            if (count > seenCount && (nearBottom || mine)) {
                listState.animateScrollToItem(count - 1)
            } else if (!mine) {
                unseen++
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
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(atBottom, vm.atLatest) { if (atBottom && vm.atLatest) unseen = 0 }
    // Reading position on the scrubber: the day of the row at the top of the list.
    val readingPos by remember(rows) {
        derivedStateOf {
            val tl = vm.timeline
            val ts = rows.getOrNull(listState.firstVisibleItemIndex)?.ts
            if (tl == null || ts == null || ts <= 0.0) 0.0 else posOfTs(tl, ts.toLong())
        }
    }
    // The day of the topmost row, while that day's inline separator is above the viewport.
    val floatingDay by remember(rows) {
        derivedStateOf {
            val idx = listState.firstVisibleItemIndex
            val off = listState.firstVisibleItemScrollOffset
            val row = rows.getOrNull(idx)
            when {
                row == null || row.ts <= 0.0 -> null
                idx == 0 && off == 0 -> null
                row is DayRow && off == 0 -> null
                else -> row.ts
            }
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
    val currentRows by rememberUpdatedState(rows)
    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        ConversationHeader(
            name = vm.currentName,
            subtitle = if (group) "Group" else waPhoneSubtitle(vm.currentJid),
            avatar = { vm.currentJid?.let { Avatar(vm.currentName, it, vm, size = 40.dp) } },
            unread = vm.currentUnread,
            canGoToDate = vm.timeline != null,
            pinned = vm.currentPinned,
            localPin = vm.currentLocalPin,
            jumpingFirst = vm.jumpingFirst,
            c = c,
            onBack = { vm.closeChat() },
            onMarkRead = { vm.markRead() },
            onGoToDate = { showDatePicker = true },
            onJumpFirst = { vm.jumpToFirst() },
            onPin = { vm.togglePin() },
        )

        vm.message?.let { Toast(it) }

        Column(Modifier.weight(1f).fillMaxWidth().chatWallpaper(c)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val maxBubble = maxWidth * 0.78f
                val env = remember(c, group, nowSec, reduced, byId, names, vm.reactions, maxBubble, popEnabled, vm.imageLoader) {
                    ChatEnv(
                        colors = c,
                        group = group,
                        nowSec = nowSec,
                        reduced = reduced,
                        imageLoader = vm.imageLoader,
                        mediaUrl = { m, thumb -> vm.mediaUrl(m, thumb) },
                        byId = byId,
                        names = names,
                        reactions = vm.reactions,
                        maxBubble = maxBubble,
                        voice = { m ->
                            val v = vm.voice
                            val cur = v.currentId == m.id
                            VoiceUi(
                                current = cur,
                                loading = v.loadingId == m.id,
                                playing = cur && v.isPlaying,
                                positionMs = if (cur) v.positionMs else 0,
                                durationMs = if (cur) v.durationMs else 0,
                                speed = v.speed,
                            )
                        },
                        onOpenImage = { viewing = it },
                        onVoiceToggle = { m -> vm.voice.toggle(m.id, vm.mediaUrl(m)) },
                        onVoiceSeek = { _, f -> vm.voice.seekTo((f * vm.voice.durationMs).toInt()) },
                        onVoiceSpeed = { vm.voice.cycleSpeed() },
                        onQuoteTap = { id ->
                            val i = waRowIndexOf(currentRows, id)
                            if (i >= 0) scope.launch {
                                listState.animateScrollToItem(i)
                                flashId = id
                                delay(700)
                                if (flashId == id) flashId = null
                            }
                        },
                        popEnabled = popEnabled,
                        reactionSeen = reactionSeen,
                    )
                }
                if (vm.loadingMessages && vm.messages.isEmpty()) {
                    LoadingBox()
                } else {
                    // Stable keys make the list keep its position when older messages are prepended.
                    LazyColumn(
                        state = listState,
                        // Leave room on the right for the scrubber track.
                        contentPadding = PaddingValues(
                            end = if (vm.timeline != null) 28.dp else 0.dp, top = 8.dp, bottom = 8.dp,
                        ),
                    ) {
                        items(rows, key = { it.key }, contentType = { it::class }) { row ->
                            val anim = if (reduced) Modifier else Modifier.animateItem(
                                fadeInSpec = null, fadeOutSpec = null, placementSpec = moveSpring(),
                            )
                            when (row) {
                                is DayRow -> DayRowItem(row, env, waDayLabel(row.ts, nowSec), anim)
                                is MsgRow -> MessageItem(
                                    row, env,
                                    fresh = row.msg.id.isNotBlank() && row.msg.id in vm.freshIds,
                                    flash = row.msg.id.isNotBlank() && row.msg.id == flashId,
                                    modifier = anim,
                                )
                            }
                        }
                    }
                }
                // Floating date: the day of the topmost message once its inline separator has scrolled away.
                Crossfade(
                    targetState = floatingDay?.let { waDayLabel(it, nowSec) },
                    animationSpec = tween(140),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    label = "floatingDay",
                ) { label -> if (label != null) DayPill(label, c) }

                vm.timeline?.let { tl ->
                    TimelineScrubber(
                        tl = tl,
                        readingPos = readingPos,
                        busy = vm.jumpingDate,
                        onJump = { vm.jumpToTs(it) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, bottom = 64.dp),
                    )
                }
                JumpToLatestFab(
                    visible = !atBottom || !vm.atLatest,
                    count = unseen,
                    loading = vm.jumping,
                    highlight = !vm.atLatest,
                    c = c,
                    reduced = reduced,
                    onClick = { vm.jumpToLatest() },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                )
            }

            vm.uploading?.let { name ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
                    Spacer(Modifier.size(8.dp))
                    Text(name, style = chatSecondaryStyle(c.meta), maxLines = 1)
                }
            }

            MessageInput(
                vm = vm,
                onSend = { vm.send(it) },
                onAttach = { uri, caption -> vm.sendMedia(uri, caption) },
                displayName = vm::displayName,
                enabled = !vm.sending,
                c = c,
                reduced = reduced,
            )
        }
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
private fun MessageInput(
    vm: WhatsAppViewModel,
    onSend: (String) -> Unit,
    onAttach: (Uri, String) -> Unit,
    displayName: (Uri) -> String,
    enabled: Boolean,
    c: ChatColors,
    reduced: Boolean,
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
            onTextChange = { text = it },
            enabled = enabled,
            c = c,
            reduced = reduced,
            onSend = { if (text.isNotBlank()) { onSend(text); text = "" } },
            onAttach = { picker.launch("*/*") },
            onMic = onMic,
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
private fun Avatar(name: String, jid: String, vm: WhatsAppViewModel, size: Dp = 40.dp) {
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
private fun Toast(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}
