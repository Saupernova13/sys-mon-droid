package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.ui.common.LoadingBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The rows the list shows and the lookups derived from the loaded messages. */
private class ConversationData(
    val rows: List<WaRow>,
    val nowSec: Double,
    val byId: Map<String, WaMessage>,
    val names: Map<String, String>,
)

@Composable
private fun rememberConversationData(messages: List<WaMessage>): ConversationData {
    val rows = remember(messages) { buildRows(messages) }
    val nowSec = remember(messages) { System.currentTimeMillis() / 1000.0 }
    val byId = remember(messages) { messages.filter { it.id.isNotBlank() }.associateBy { it.id } }
    val names = remember(messages) { waNameBySender(messages) }
    return ConversationData(rows, nowSec, byId, names)
}

/**
 * Where the reader is in the list, for the jump button, the floating date and the scrubber. It holds the
 * states, not their values, so scrolling recomposes only the overlays that read them.
 */
@Stable
private class ConversationScroll(
    private val atBottomState: State<Boolean>,
    private val unseenState: State<Int>,
    private val readingPosState: State<Double>,
    private val floatingDayState: State<Double?>,
) {
    val atBottom: Boolean get() = atBottomState.value
    val unseen: Int get() = unseenState.value
    val readingPos: Double get() = readingPosState.value
    val floatingDay: Double? get() = floatingDayState.value
}

/** The open conversation: header, message area, composer, and the sheets and dialogs over it. */
@Composable
internal fun ConversationView(vm: WhatsAppViewModel) {
    var viewing by remember { mutableStateOf<WaMessage?>(null) }
    // The long-pressed message's menu, the forward picker, the info sheet.
    var menuMsg by remember(vm.currentJid) { mutableStateOf<WaMessage?>(null) }
    var forwardMsg by remember(vm.currentJid) { mutableStateOf<WaMessage?>(null) }
    var infoMsg by remember(vm.currentJid) { mutableStateOf<WaMessage?>(null) }
    // The composer's text lives here so the Edit action can seed it.
    var composerText by remember(vm.currentJid) { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var flashId by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val c = rememberChatColors()
    val reduced = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val data = rememberConversationData(vm.messages)
    val currentRows by rememberUpdatedState(data.rows)
    val scroll = rememberConversationScroll(vm, data.rows, listState)
    // The entrance animation is for live / sent messages only; forget them once it has played.
    LaunchedEffect(vm.freshIds) { if (vm.freshIds.isNotEmpty()) { delay(1500); vm.clearFresh() } }

    val scrollToMessage: (String) -> Unit = { id ->
        val i = waRowIndexOf(currentRows, id)
        if (i >= 0) scope.launch {
            listState.animateScrollToItem(i)
            flashId = id
            delay(700)
            if (flashId == id) flashId = null
        }
    }

    // A search hit (or an in-chat step) asked to bring a message into view; tick 0 is "never asked".
    LaunchedEffect(vm.focusTick) { if (vm.focusTick > 0) vm.focusId?.let(scrollToMessage) }

    Column(Modifier.fillMaxSize().imePadding()) {
        // While in-chat search is open its top bar replaces the header (and its bottom bar the composer).
        if (vm.search.inChat.open) InChatSearchTopBar(vm.search.inChat, c)
        else ConversationHeaderHost(vm, c, onGoToDate = { showDatePicker = true })
        vm.message?.let { Toast(it) }
        Column(Modifier.weight(1f).fillMaxWidth().chatWallpaper(c)) {
            MessageArea(
                vm = vm, data = data, scroll = scroll, listState = listState, c = c, reduced = reduced,
                flashId = flashId, menuMsg = menuMsg,
                onOpenImage = { viewing = it },
                onQuoteTap = scrollToMessage,
                onMenuMsgChange = { menuMsg = it },
                onMenuAction = { m, act ->
                    when (act) {
                        WaMenuAction.REPLY -> vm.setReply(m)
                        WaMenuAction.COPY -> clipboard.setText(AnnotatedString(waCaptionOf(m)))
                        WaMenuAction.FORWARD -> forwardMsg = m
                        WaMenuAction.EDIT -> { vm.startEdit(m); composerText = m.text }
                        WaMenuAction.SAVE -> vm.saveImage(m)
                        WaMenuAction.INFO -> infoMsg = m
                        WaMenuAction.DELETE -> Unit
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            if (vm.search.inChat.open) {
                InChatSearchBottomBar(vm.search.inChat, c)
            } else {
                vm.uploading?.let { UploadingRow(it, c) }
                ComposerHost(
                    vm = vm, names = data.names, c = c, reduced = reduced,
                    text = composerText, onTextChange = { composerText = it },
                )
            }
        }
    }

    viewing?.let { tapped ->
        GalleryHost(
            vm = vm, tapped = tapped, data = data,
            onShowInChat = { m -> viewing = null; scrollToMessage(m.id) },
            onClose = { viewing = null },
        )
    }
    forwardMsg?.let { fm ->
        ForwardSheet(
            chats = vm.chats,
            c = c,
            onPick = { to -> vm.forward(fm, to); forwardMsg = null },
            onDismiss = { forwardMsg = null },
        )
    }
    infoMsg?.let { InfoSheet(m = it, names = data.names, c = c, onDismiss = { infoMsg = null }) }
    val tl = vm.timeline
    if (showDatePicker && tl != null) {
        GoToDateDialog(
            tl = tl,
            initialDay = dayAt(tl, scroll.readingPos).day,
            onPick = { vm.jumpToTs(it); showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }
}

@Composable
private fun ConversationHeaderHost(vm: WhatsAppViewModel, c: ChatColors, onGoToDate: () -> Unit) {
    val jid = vm.currentJid
    ConversationHeader(
        name = vm.currentName,
        subtitle = if (waIsGroup(jid)) "Group" else waPhoneSubtitle(jid),
        avatar = { jid?.let { Avatar(vm.currentName, it, vm, size = 40.dp) } },
        unread = vm.currentUnread,
        canGoToDate = vm.timeline != null,
        pinned = vm.currentPinned,
        localPin = vm.currentLocalPin,
        jumpingFirst = vm.jumpingFirst,
        searchOn = vm.search.inChat.open,
        c = c,
        onBack = { vm.closeChat() },
        onMarkRead = { vm.markRead() },
        onGoToDate = onGoToDate,
        onJumpFirst = { vm.jumpToFirst() },
        onPin = { vm.togglePin() },
        onSearch = { vm.search.inChat.toggle() },
    )
}

/** The list with its overlays (floating date, scrubber, jump button) and the long-press menu on top. */
@Composable
private fun MessageArea(
    vm: WhatsAppViewModel,
    data: ConversationData,
    scroll: ConversationScroll,
    listState: LazyListState,
    c: ChatColors,
    reduced: Boolean,
    flashId: String?,
    menuMsg: WaMessage?,
    onOpenImage: (WaMessage) -> Unit,
    onQuoteTap: (String) -> Unit,
    onMenuMsgChange: (WaMessage?) -> Unit,
    onMenuAction: (WaMessage, WaMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Chips pop when they first appear or change, but not while the conversation is just opening.
    var popEnabled by remember(vm.currentJid) { mutableStateOf(false) }
    LaunchedEffect(vm.currentJid, data.rows.isNotEmpty()) { if (data.rows.isNotEmpty()) { delay(700); popEnabled = true } }
    val reactionSeen = remember(vm.currentJid) { HashMap<String, String>() }
    val rowBounds = remember(vm.currentJid) { HashMap<String, Rect>() }

    BoxWithConstraints(modifier) {
        val maxBubble = maxWidth * 0.78f
        val env = rememberChatEnv(
            vm, data, c, reduced, maxBubble, popEnabled, rowBounds, reactionSeen,
            onOpenImage, onQuoteTap, onLongPress = { onMenuMsgChange(it) },
        )
        MessageList(vm, data, env, listState, reduced, flashId)
        ScrollOverlays(vm, data, scroll, c, reduced)
        // Last in the box, so the menu draws over the list and the jump button.
        menuMsg?.let { mm ->
            data.rows.filterIsInstance<MsgRow>().firstOrNull { it.msg.id == mm.id }?.let { mrow ->
                MessageMenuOverlay(
                    row = mrow,
                    env = env,
                    danger = MaterialTheme.colorScheme.error,
                    myEmoji = env.reactions[mm.id]?.get("me")?.emoji ?: "",
                    onReact = { m, emoji -> vm.react(m, emoji) },
                    onAction = onMenuAction,
                    onDelete = { m, everyone -> vm.removeMessage(m, everyone) },
                    onDismiss = { onMenuMsgChange(null) },
                )
            }
        }
    }
}

/** What every bubble needs: colours, lookups, voice playback and the message gestures. */
@Composable
private fun rememberChatEnv(
    vm: WhatsAppViewModel,
    data: ConversationData,
    c: ChatColors,
    reduced: Boolean,
    maxBubble: Dp,
    popEnabled: Boolean,
    rowBounds: HashMap<String, Rect>,
    reactionSeen: HashMap<String, String>,
    onOpenImage: (WaMessage) -> Unit,
    onQuoteTap: (String) -> Unit,
    onLongPress: (WaMessage) -> Unit,
): ChatEnv {
    val group = waIsGroup(vm.currentJid)
    val marks = vm.search.inChat.terms
    val range = vm.search.rangeHighlight
    return remember(c, group, data.nowSec, reduced, data.byId, data.names, vm.reactions, maxBubble, popEnabled, vm.imageLoader, rowBounds, marks, range) {
        ChatEnv(
            colors = c,
            group = group,
            nowSec = data.nowSec,
            reduced = reduced,
            imageLoader = vm.imageLoader,
            mediaUrl = { m, thumb -> vm.mediaUrl(m, thumb) },
            byId = data.byId,
            names = data.names,
            reactions = vm.reactions,
            maxBubble = maxBubble,
            voice = { m -> voiceUiFor(vm.voice, m.id) },
            onOpenImage = onOpenImage,
            onVoiceToggle = { m -> vm.voice.toggle(m.id, vm.mediaUrl(m)) },
            onVoiceSeek = { _, f -> vm.voice.seekTo((f * vm.voice.durationMs).toInt()) },
            onVoiceSpeed = { vm.voice.cycleSpeed() },
            onQuoteTap = onQuoteTap,
            onLongPress = onLongPress,
            onReply = { m -> vm.setReply(m) },
            onReact = { m, emoji -> vm.react(m, emoji) },
            rowBounds = rowBounds,
            popEnabled = popEnabled,
            reactionSeen = reactionSeen,
            marks = marks,
            range = range,
        )
    }
}

private fun voiceUiFor(v: VoiceNotePlayer, id: String): VoiceUi {
    val cur = v.currentId == id
    return VoiceUi(
        current = cur,
        loading = v.loadingId == id,
        playing = cur && v.isPlaying,
        positionMs = if (cur) v.positionMs else 0,
        durationMs = if (cur) v.durationMs else 0,
        speed = v.speed,
    )
}

@Composable
private fun MessageList(
    vm: WhatsAppViewModel,
    data: ConversationData,
    env: ChatEnv,
    listState: LazyListState,
    reduced: Boolean,
    flashId: String?,
) {
    if (vm.loadingMessages && vm.messages.isEmpty()) {
        LoadingBox()
        return
    }
    // Stable keys make the list keep its position when older messages are prepended.
    LazyColumn(
        state = listState,
        // Leave room on the right for the scrubber track.
        contentPadding = PaddingValues(end = if (vm.timeline != null) 28.dp else 0.dp, top = 8.dp, bottom = 8.dp),
    ) {
        items(data.rows, key = { it.key }, contentType = { it::class }) { row ->
            val anim = if (reduced) Modifier else Modifier.animateItem(
                fadeInSpec = null, fadeOutSpec = null, placementSpec = moveSpring(),
            )
            when (row) {
                is DayRow -> DayRowItem(row, env, waDayLabel(row.ts, data.nowSec), anim)
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

@Composable
private fun BoxScope.ScrollOverlays(
    vm: WhatsAppViewModel,
    data: ConversationData,
    scroll: ConversationScroll,
    c: ChatColors,
    reduced: Boolean,
) {
    // Floating date: the day of the topmost message once its inline separator has scrolled away.
    Crossfade(
        targetState = scroll.floatingDay?.let { waDayLabel(it, data.nowSec) },
        animationSpec = tween(140),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        label = "floatingDay",
    ) { label -> if (label != null) DayPill(label, c) }

    vm.timeline?.let { tl ->
        TimelineScrubber(
            tl = tl,
            readingPos = scroll.readingPos,
            busy = vm.jumpingDate,
            onJump = { vm.jumpToTs(it) },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, bottom = 64.dp),
            marks = remember(tl, vm.search.inChat.days, vm.search.rangeHighlight) {
                scrubberMarks(tl, vm.search.inChat.days, vm.search.rangeHighlight)
            },
        )
    }
    JumpToLatestFab(
        visible = !scroll.atBottom || !vm.atLatest,
        count = scroll.unseen,
        loading = vm.jumping,
        highlight = !vm.atLatest,
        c = c,
        reduced = reduced,
        onClick = { vm.jumpToLatest() },
        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
    )
}

@Composable
private fun UploadingRow(name: String, c: ChatColors) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
        Spacer(Modifier.size(8.dp))
        Text(name, style = chatSecondaryStyle(c.meta), maxLines = 1)
    }
}

@Composable
private fun ComposerHost(
    vm: WhatsAppViewModel,
    names: Map<String, String>,
    c: ChatColors,
    reduced: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
) {
    MessageInput(
        vm = vm,
        text = text,
        onTextChange = onTextChange,
        chip = vm.composerChip(names),
        onChipCancel = {
            val wasEditing = vm.editingMsg != null
            vm.clearComposerContext()
            if (wasEditing) onTextChange("")
        },
        onSend = { vm.send(it); onTextChange("") },
        onAttach = { uri, caption -> vm.sendMedia(uri.toString(), caption) },
        displayName = { vm.displayName(it.toString()) },
        enabled = !vm.sending,
        c = c,
        reduced = reduced,
    )
}

@Composable
private fun GalleryHost(
    vm: WhatsAppViewModel,
    tapped: WaMessage,
    data: ConversationData,
    onShowInChat: (WaMessage) -> Unit,
    onClose: () -> Unit,
) {
    val photos = remember(vm.messages) { waGalleryItems(vm.messages) }
    if (photos.isEmpty()) return
    ChatGallery(
        photos = photos,
        startIndex = photos.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0),
        names = data.names,
        nowSec = data.nowSec,
        url = { m -> vm.mediaUrl(m, false) },
        thumbUrl = { m -> vm.mediaUrl(m, true) },
        imageLoader = vm.imageLoader,
        onSave = { m -> vm.saveImage(m) },
        onShowInChat = onShowInChat,
        onClose = onClose,
    )
}

/** Scroll effects (open at the newest, jump to first, follow live, page in) and the derived reading position. */
@Composable
private fun rememberConversationScroll(
    vm: WhatsAppViewModel,
    rows: List<WaRow>,
    listState: LazyListState,
): ConversationScroll {
    ScrollJumpEffects(vm, rows, listState)
    PagingEffects(vm, listState)
    val atBottom = remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    val unseen = rememberLiveFollow(vm, rows, listState, atBottom)
    // Reading position on the scrubber: the day of the row at the top of the list.
    val readingPos = remember(rows) {
        derivedStateOf {
            val tl = vm.timeline
            val ts = rows.getOrNull(listState.firstVisibleItemIndex)?.ts
            if (tl == null || ts == null || ts <= 0.0) 0.0 else posOfTs(tl, ts.toLong())
        }
    }
    // The day of the topmost row, while that day's inline separator is above the viewport.
    val floatingDay = remember(rows) {
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
    return remember(atBottom, unseen, readingPos, floatingDay) {
        ConversationScroll(atBottom, unseen, readingPos, floatingDay)
    }
}

@Composable
private fun ScrollJumpEffects(vm: WhatsAppViewModel, rows: List<WaRow>, listState: LazyListState) {
    // Open (and send / jump) at the newest message.
    LaunchedEffect(vm.scrollTick) {
        val last = rows.lastIndex
        if (last >= 0) listState.scrollToItem(last, Int.MAX_VALUE) // offset clamps to the true end, so a tall last message is fully shown
    }
    // Jump to first: the replaced window starts at its first item.
    LaunchedEffect(vm.topTick) {
        if (vm.topTick > 0 && rows.isNotEmpty()) listState.scrollToItem(0)
    }
}

@Composable
private fun PagingEffects(vm: WhatsAppViewModel, listState: LazyListState) {
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
}

/**
 * A live message follows the bottom only if the user was already there (or sent it). Gated on
 * liveTick so appending a forward page or merging the newest page never yanks the list down.
 * Returns how many live messages arrived while the reader was elsewhere.
 */
@Composable
private fun rememberLiveFollow(
    vm: WhatsAppViewModel,
    rows: List<WaRow>,
    listState: LazyListState,
    atBottom: State<Boolean>,
): State<Int> {
    var seenCount by remember { mutableIntStateOf(rows.size) }
    var seenNewest by remember { mutableStateOf(vm.messages.lastOrNull()?.id) }
    var seenLive by remember { mutableIntStateOf(vm.liveTick) }
    val unseen = remember(vm.currentJid) { mutableIntStateOf(0) }
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
                unseen.intValue++
            }
        }
        seenCount = count
        seenNewest = newestId
    }
    val bottom = atBottom.value
    LaunchedEffect(bottom, vm.atLatest) { if (bottom && vm.atLatest) unseen.intValue = 0 }
    return unseen
}
