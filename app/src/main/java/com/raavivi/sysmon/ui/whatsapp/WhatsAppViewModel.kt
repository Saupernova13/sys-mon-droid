package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.data.whatsapp.UploadSource
import com.raavivi.sysmon.core.data.whatsapp.WhatsAppRepository
import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.model.WaContact
import com.raavivi.sysmon.core.model.WaConvResult
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaSearchRow
import com.raavivi.sysmon.core.model.WaStatus
import com.raavivi.sysmon.core.net.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class WhatsAppViewModel(
    private val repo: WhatsAppRepository,
    private val uploads: UploadSource,
    private val mediaTools: WhatsAppMediaTools,
    val imageLoader: ImageLoader,
    voiceFactory: (scope: CoroutineScope, onMessage: (String) -> Unit) -> VoiceNotePlayer,
    recorderFactory: (scope: CoroutineScope, onMessage: (String) -> Unit) -> VoiceRecorder,
    private val nowSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 },
) : ViewModel() {

    var status by mutableStateOf<WaStatus?>(null)
        private set
    var chats by mutableStateOf<List<WaChat>>(emptyList())
        private set
    var loadingChats by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    var currentJid by mutableStateOf<String?>(null)
        private set
    var currentName by mutableStateOf("")
        private set
    var currentPinned by mutableStateOf(false)
        private set
    /** The loaded slice of the open conversation and its paging state; the rules live in [ConversationWindow]. */
    private var window by mutableStateOf(ConversationWindow())

    val messages: List<WaMessage> get() = window.messages
    var loadingMessages by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set

    /** True while "Jump to latest" is refreshing the chat and fetching its media. */
    var jumping by mutableStateOf(false)
        private set

    /** Bumped whenever the conversation should scroll to its newest message (open, send, jump). */
    var scrollTick by mutableIntStateOf(0)
        private set

    /** True while "Jump to first" is fetching the chat's oldest page. */
    var jumpingFirst by mutableStateOf(false)
        private set

    /** True while a scrubber / go-to-date jump is fetching its page. */
    var jumpingDate by mutableStateOf(false)
        private set

    /** The open chat's per-day layout for the scrubber; null while loading, on failure, or with under two days. */
    var timeline by mutableStateOf<WaTimeline?>(null)
        private set

    /** Bumped when the conversation should scroll to its first item (jump to first / date). */
    var topTick by mutableIntStateOf(0)
        private set

    /** Bumped only when a live message is appended to the open conversation (drives follow-the-bottom). */
    var liveTick by mutableIntStateOf(0)
        private set

    /** The message search surfaces: the Search view, the in-chat bar, a tapped conversation's range. */
    val search = WhatsAppSearch(repo, viewModelScope, { currentJid }, ::focusHit, { message = it })

    /** The message to scroll to and flash (a search hit); [focusTick] bumps for every such request. */
    var focusId by mutableStateOf<String?>(null)
        private set
    var focusTick by mutableIntStateOf(0)
        private set

    /** Whether the loaded window includes the chat's newest message; false after jumping to the first page. */
    val atLatest: Boolean get() = window.atLatest

    private var loadingOlder = false
    private var loadingNewer = false

    /** Bumped whenever the window is replaced, so a page still in flight for the old window is dropped. */
    private var windowEpoch = 0

    /** Display name of the file currently being uploaded, or null when idle. */
    var uploading by mutableStateOf<String?>(null)
        private set

    var contacts by mutableStateOf<List<WaContact>>(emptyList())
        private set

    /** Reactions folded from the loaded pages and live frames; they never become list items. */
    val reactions: WaReactions get() = window.reactions

    /** Ids of messages that arrived live or were just sent: only these get the entrance animation. */
    val freshIds: Set<String> get() = window.freshIds

    fun clearFresh() { window = window.clearedFresh() }

    val currentLocalPin: Boolean get() = chats.firstOrNull { it.jid == currentJid }?.localPin ?: false

    /** Per-jid counter folded into the avatar's cache key; bumped when a refresh cached a new picture. */
    var avatarVersions by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    /** Jids already offered to the server's avatar refresh this session. */
    private val avatarTried = mutableSetOf<String>()

    val voice: VoiceNotePlayer = voiceFactory(viewModelScope) { message = it }

    val recorder: VoiceRecorder = recorderFactory(viewModelScope) { message = it }

    // The screen re-requests both loads from several places at once — the Refresh
    // button, the status banner's retry, and a live message for a chat the first
    // load hasn't listed yet — so each runs as a SingleFlight: one request in the
    // air, later asks coalesced into exactly one follow-up.
    private val statusLoad = SingleFlight(viewModelScope) {
        (repo.status() as? ApiResult.Ok)?.let { status = it.value }
    }
    private val chatsLoad = SingleFlight(viewModelScope) {
        loadingChats = true
        error = null
        when (val r = repo.chats()) {
            is ApiResult.Ok -> chats = waSortChats(r.value)
            is ApiResult.Err -> error = r.message
        }
        loadingChats = false
        refreshAvatars()
    }

    init {
        loadStatus()
        loadChats()
        viewModelScope.launch { repo.liveMessages().collect(::handleLive) }
    }

    fun mediaUrl(msg: WaMessage, thumb: Boolean = false): String = repo.mediaUrl(msg.chat, msg.id, thumb)

    fun avatarUrl(jid: String): String = repo.avatarUrl(jid)

    fun loadStatus() {
        statusLoad.trigger()
    }

    fun loadChats() {
        chatsLoad.trigger()
    }

    /** One load at a time: a trigger while one runs only asks for one more
     *  after it, so bursts of triggers never stack concurrent requests. */
    private class SingleFlight(private val scope: CoroutineScope, private val run: suspend () -> Unit) {
        private var job: Job? = null
        private var wanted = false

        fun trigger() {
            if (job?.isActive == true) {
                wanted = true
                return
            }
            start()
        }

        private fun start() {
            wanted = false
            job = scope.launch {
                run()
                if (wanted) start()
            }
        }
    }

    /**
     * Ask the server to fetch avatars it has not cached, then re-point the rows
     * it filled. The server does at most `limit` per call, so go again while it
     * reports it hit the limit (bounded). Chats already offered are not resent.
     */
    private suspend fun refreshAvatars() {
        val pending = chats.map { it.jid }.filter { it.isNotBlank() && it !in avatarTried }.toMutableList()
        if (pending.isEmpty()) return
        avatarTried += pending
        repeat(MAX_AVATAR_PASSES) {
            if (pending.isEmpty()) return
            val ok = (repo.refreshAvatars(pending.toList(), AVATAR_LIMIT) as? ApiResult.Ok)?.value ?: return
            if (ok.updated.isNotEmpty()) {
                avatarVersions = avatarVersions + ok.updated.associateWith { (avatarVersions[it] ?: 0) + 1 }
                pending.removeAll(ok.updated.toSet())
            }
            if (ok.attempted < ok.limit) return
        }
    }

    fun openChat(jid: String, name: String) {
        beginChat(jid, name) { loadMessages(jid) }
    }

    /** Switch to [jid]: reset the window, voice and search state, run [load] for the messages, then the timeline. */
    private fun beginChat(jid: String, name: String, load: () -> Unit) {
        currentJid = jid
        currentName = name
        currentPinned = chats.firstOrNull { it.jid == jid }?.pinned ?: false
        resetWindow()
        voice.release()
        recorder.cancel()
        timeline = null
        search.onChatChanged()
        load()
        viewModelScope.launch {
            val r = repo.timeline(jid)
            if (currentJid != jid) return@launch
            // A failure leaves the timeline null: the scrubber and date picker just stay hidden.
            if (r is ApiResult.Ok) timeline = buildTimeline(r.value)
        }
    }

    /**
     * Open [jid] at a moment instead of its newest page: the page that starts just before [ts], so a hit or a
     * conversation start sits near the top. [focusId] is scrolled to and flashed; [range] (a conversation's
     * start..end, unix seconds) is tinted in the chat.
     */
    fun openChatAt(jid: String, name: String, ts: Long, focusId: String? = null, range: LongRange? = null) {
        beginChat(jid, name) {
            loadingMessages = true
            viewModelScope.launch {
                loadWindowAt(jid, ts, focusId)
                loadingMessages = false
            }
        }
        search.rangeHighlight = range
    }

    /** A Search-view message hit: open its chat at that message, then carry the query on in the in-chat bar. */
    fun openSearchHit(hit: WaSearchRow) {
        val q = search.query.trim()
        openChatAt(hit.chatJid, searchChatName(hit.chatJid, hit.chatName), hit.ts, hit.msgId)
        search.inChat.continueWith(q, hit.msgId)
    }

    /** A conversation card: open its chat at the node's start and tint the node's range. */
    fun openConversation(conv: WaConvResult) {
        openChatAt(conv.chat, searchChatName(conv.chat, conv.chatName), conv.startTs, range = conv.startTs..conv.endTs)
    }

    /** The name a search result shows for [jid]: the chat list's, else the hit's own, else the jid. */
    fun searchChatName(jid: String, fallback: String): String =
        chats.firstOrNull { it.jid == jid }?.name?.takeIf { it.isNotBlank() } ?: fallback.ifBlank { jid }

    /** Bring an in-chat search hit into view: scroll to it when loaded, else open the window at it. */
    fun focusHit(hit: WaSearchRow) {
        val jid = currentJid ?: return
        if (messages.any { it.id == hit.msgId }) {
            focusId = hit.msgId
            focusTick++
            return
        }
        jumpingDate = true
        viewModelScope.launch {
            try {
                loadWindowAt(jid, hit.ts, hit.msgId)
            } finally {
                jumpingDate = false
            }
        }
    }

    /** Replace the window with the page just before [ts] (one request) and scroll to [focus], else to the top. */
    private suspend fun loadWindowAt(jid: String, ts: Long, focus: String?) {
        val from = ts.toDouble() - SEEK_MARGIN_SEC
        val r = repo.messages(chat = jid, limit = WA_PAGE_LIMIT, after = waInclusiveAfter(from), asc = true)
        if (currentJid != jid) return
        when (r) {
            is ApiResult.Ok -> {
                val page = r.value
                windowEpoch++
                val startsAtFirst = timeline?.let { from <= it.first.firstTs } ?: false
                window = window.replacedByPage(page, reachedFirst = startsAtFirst)
                mediaTools.prefetch(page)
                if (focus != null) {
                    focusId = focus
                    focusTick++
                } else {
                    topTick++
                }
            }
            is ApiResult.Err -> message = "Open failed: ${r.message}"
        }
    }

    /** Whether the open chat has unread messages (enables "Mark as read"). */
    val currentUnread: Int get() = chats.firstOrNull { it.jid == currentJid }?.unread ?: 0

    /** The server no longer marks a chat read on its own; this does it explicitly. */
    fun markRead() {
        val jid = currentJid ?: return
        markReadChat(jid)
    }

    /** Download an image message and store it in the public Pictures collection. */
    fun saveImage(msg: WaMessage) {
        viewModelScope.launch {
            message = "Saving..."
            message = runCatching { "Saved to ${mediaTools.saveImage(msg)}" }
                .getOrElse { "Save failed: ${it.message ?: "unknown error"}" }
        }
    }

    fun closeChat() {
        voice.release()
        recorder.cancel()
        currentJid = null
        window = ConversationWindow()
        timeline = null
        resetWindow()
        search.onChatChanged()
    }

    /** Back to "newest page, nothing in flight" when a conversation opens or closes. */
    private fun resetWindow() {
        windowEpoch++
        window = window.reopened()
        loadingOlder = false
        loadingNewer = false
        jumpingFirst = false
        jumpingDate = false
    }

    private fun loadMessages(jid: String) {
        loadingMessages = true
        viewModelScope.launch {
            if (fetchNewest(jid)) scrollTick++
            loadingMessages = false
        }
    }

    /**
     * Replace the open list with the chat's newest page, or with [merge] fold it into the current
     * window (catching up live messages missed while browsing elsewhere). Either way the window now
     * includes the newest message. False on failure or if the chat (or, when merging, the window)
     * changed meanwhile.
     */
    private suspend fun fetchNewest(jid: String, merge: Boolean = false): Boolean {
        val epoch = windowEpoch
        val r = repo.messages(chat = jid, limit = WA_PAGE_LIMIT)
        if (currentJid != jid) return false
        if (merge && windowEpoch != epoch) return false
        return when (r) {
            is ApiResult.Ok -> {
                val page = r.value
                if (merge) {
                    window = window.mergedWithNewest(page)
                } else {
                    windowEpoch++
                    window = window.replacedByNewest(page)
                }
                mediaTools.prefetch(page)
                true
            }
            is ApiResult.Err -> { error = r.message; false }
        }
    }

    /** Reload the newest page, have the server fetch missing media, reload again, and scroll down. */
    fun jumpToLatest() {
        val jid = currentJid ?: return
        if (jumping) return
        jumping = true
        viewModelScope.launch {
            try {
                if (fetchNewest(jid)) scrollTick++
                if (currentJid != jid) return@launch
                val r = repo.fetchLatest(jid)
                if (r is ApiResult.Err && currentJid == jid) message = "Latest failed: ${r.message}"
                if (fetchNewest(jid)) scrollTick++
            } finally {
                jumping = false
            }
        }
    }

    /** Replace the window with the chat's oldest page (one request, no media fetch) and scroll to the top. */
    fun jumpToFirst() {
        val jid = currentJid ?: return
        if (jumpingFirst) return
        jumpingFirst = true
        viewModelScope.launch {
            try {
                val r = repo.messages(chat = jid, limit = WA_PAGE_LIMIT, asc = true)
                if (currentJid != jid) return@launch
                when (r) {
                    is ApiResult.Ok -> {
                        val page = r.value
                        windowEpoch++
                        window = window.replacedByPage(page, reachedFirst = true)
                        mediaTools.prefetch(page)
                        topTick++
                    }
                    is ApiResult.Err -> message = "First message failed: ${r.message}"
                }
            } finally {
                jumpingFirst = false
            }
        }
    }

    /**
     * Replace the window with the page starting at the day whose first message is [ts] (one request)
     * and scroll to its top. Used by the timeline scrubber and the go-to-date picker.
     */
    fun jumpToTs(ts: Long) {
        val jid = currentJid ?: return
        if (jumpingDate) return
        jumpingDate = true
        viewModelScope.launch {
            try {
                val r = repo.messages(
                    chat = jid, limit = WA_PAGE_LIMIT, after = waInclusiveAfter(ts.toDouble()), asc = true,
                )
                if (currentJid != jid) return@launch
                when (r) {
                    is ApiResult.Ok -> {
                        val page = r.value
                        windowEpoch++
                        val startsAtFirst = timeline?.let { ts <= it.first.firstTs } ?: false
                        window = window.replacedByPage(page, reachedFirst = startsAtFirst)
                        mediaTools.prefetch(page)
                        topTick++
                    }
                    is ApiResult.Err -> message = "Jump failed: ${r.message}"
                }
            } finally {
                jumpingDate = false
            }
        }
    }

    /**
     * Prepend the page before the oldest loaded message. The cursor is inclusive so rows sharing the
     * boundary second are not skipped; if that adds nothing, one strict retry guards against a page
     * made entirely of already-loaded rows. [silent] (the automatic scroll-to-top path) never toasts
     * "No older messages".
     */
    fun loadOlder(silent: Boolean = false) {
        val jid = currentJid ?: return
        if (loadingOlder) return
        if (window.reachedFirst) { if (!silent) message = "No older messages"; return }
        val oldest = window.oldestTs ?: return
        val epoch = windowEpoch
        loadingOlder = true
        viewModelScope.launch {
            try {
                var strict = false
                while (true) {
                    val before = ConversationWindow.olderCursor(oldest, strict)
                    val r = repo.messages(chat = jid, limit = WA_PAGE_LIMIT, before = before)
                    if (currentJid != jid || windowEpoch != epoch) return@launch
                    if (r !is ApiResult.Ok) return@launch
                    val page = r.value
                    val (merged, added) = window.merged(page)
                    window = merged
                    mediaTools.prefetch(page)
                    val outcome = ConversationWindow.settle(page.size, added, strict)
                    if (outcome == PageOutcome.STALLED) {
                        strict = true
                        continue
                    }
                    if (outcome == PageOutcome.END) {
                        window = window.withReachedFirst()
                        if (added == 0 && !silent) message = "No older messages"
                    }
                    return@launch
                }
            } finally {
                loadingOlder = false
            }
        }
    }

    /**
     * Append the page after the newest loaded message (only while the window is short of the newest).
     * Reaching the end merges in the newest page to pick up live messages skipped meanwhile.
     */
    fun loadNewer() {
        val jid = currentJid ?: return
        if (atLatest || loadingNewer) return
        val newest = window.newestTs ?: return
        val epoch = windowEpoch
        loadingNewer = true
        viewModelScope.launch {
            try {
                var strict = false
                while (true) {
                    // Strict `after` is the floor of the second: wacli then returns only later seconds.
                    val after = ConversationWindow.newerCursor(newest, strict)
                    val r = repo.messages(chat = jid, limit = WA_PAGE_LIMIT, after = after, asc = true)
                    if (currentJid != jid || windowEpoch != epoch) return@launch
                    if (r !is ApiResult.Ok) return@launch
                    val page = r.value
                    val (merged, added) = window.merged(page)
                    window = merged
                    mediaTools.prefetch(page)
                    val outcome = ConversationWindow.settle(page.size, added, strict)
                    if (outcome == PageOutcome.STALLED) {
                        strict = true
                        continue
                    }
                    if (outcome == PageOutcome.END) fetchNewest(jid, merge = true)
                    return@launch
                }
            } finally {
                loadingNewer = false
            }
        }
    }

    fun send(text: String) {
        val jid = currentJid ?: return
        if (text.isBlank() || sending) return
        // The send button doubles as the edit's confirm while an edit chip is up.
        editingMsg?.let { editMessage(it, text); return }
        val replying = replyTo
        sending = true
        viewModelScope.launch {
            when (val r = repo.sendText(to = jid, message = text, replyTo = replying?.id)) {
                is ApiResult.Ok -> { replyTo = null; loadMessages(jid) }
                is ApiResult.Err -> message = "Send failed: ${r.message}"
            }
            sending = false
        }
    }

    /** Display name of a picked document ([ref] is the opaque reference the picker produced), for the confirm dialog. */
    fun displayName(ref: String): String = uploads.displayName(ref)

    /** Upload a picked file to the open chat; the repository streams it, so a large file never sits in memory. */
    fun sendMedia(ref: String, caption: String) {
        val jid = currentJid ?: return
        if (sending || uploading != null) return
        sending = true
        viewModelScope.launch {
            val file = uploads.describe(ref)
            val size = file.size
            if (size != null && size > MAX_UPLOAD_BYTES) {
                message = "Send failed: ${file.name} is larger than 100 MiB"
                sending = false
                return@launch
            }
            uploading = "Uploading ${file.name}..."
            when (val r = repo.sendMedia(jid, file, caption)) {
                is ApiResult.Ok -> loadMessages(jid)
                is ApiResult.Err -> message = "Send failed: ${r.message}"
            }
            uploading = null
            sending = false
        }
    }

    /** Stop the recording and send it as a voice note; a too-short recording is discarded.
     *  A pending reply chip rides along, as with a text send. */
    fun sendRecording() {
        val file = recorder.finish() ?: return
        val replying = replyTo
        sendVoice(file, replying?.id)
        replyTo = null
    }

    /** Upload a recorded audio file as a WhatsApp voice note, then delete it. */
    fun sendVoice(file: File, replyTo: String?) {
        val jid = currentJid
        if (jid == null || sending || uploading != null) {
            file.delete()
            return
        }
        sending = true
        uploading = "Sending voice note..."
        viewModelScope.launch {
            when (val r = repo.sendVoice(jid, replyTo, file)) {
                is ApiResult.Ok -> loadMessages(jid)
                is ApiResult.Err -> message = "Send failed: ${r.message}"
            }
            file.delete()
            uploading = null
            sending = false
        }
    }

    fun togglePin() {
        val jid = currentJid ?: return
        if (currentLocalPin) return   // a sys-mon pin cannot be changed from here
        val next = !currentPinned
        viewModelScope.launch {
            when (val r = repo.setPinned(jid, next)) {
                is ApiResult.Ok -> {
                    currentPinned = next
                    chats = chats.map { if (it.jid == jid) it.copy(pinned = next) else it }.let(::waSortChats)
                }
                is ApiResult.Err -> message = "Pin failed: ${r.message}"
            }
        }
    }

    fun searchContacts(q: String) {
        if (q.isBlank()) { contacts = emptyList(); return }
        viewModelScope.launch {
            (repo.searchContacts(q) as? ApiResult.Ok)?.let { contacts = it.value }
        }
    }

    // -- piece B: replies, edits, reactions, deletes, forward, local pins -------------

    /** The chat-row menu's WhatsApp pin toggle (never offered for sys-mon-pinned chats). */
    fun togglePinFor(chat: WaChat) {
        if (chat.localPin) return
        val next = !chat.pinned
        viewModelScope.launch {
            when (val r = repo.setPinned(chat.jid, next)) {
                is ApiResult.Ok -> chats = chats.map { if (it.jid == chat.jid) it.copy(pinned = next) else it }.let(::waSortChats)
                is ApiResult.Err -> message = "Pin failed: ${r.message}"
            }
        }
    }

    /** Mark a chat read: the open one from the conversation menu, or another from the chat-row menu. */
    fun markReadChat(jid: String) {
        val cleared = chats.firstOrNull { it.jid == jid }?.unread ?: 0
        if (cleared <= 0) return
        viewModelScope.launch {
            when (val r = repo.markRead(jid)) {
                is ApiResult.Ok -> {
                    chats = chats.map { if (it.jid == jid) it.copy(unread = 0) else it }
                    status = status?.let { it.copy(unreadTotal = (it.unreadTotal - cleared).coerceAtLeast(0)) }
                }
                is ApiResult.Err -> message = "Mark read failed: ${r.message}"
            }
        }
    }

    /** The message being replied to; the composer shows its chip until sent or cancelled. */
    var replyTo by mutableStateOf<WaMessage?>(null)
        private set

    /** The message being edited (its text was seeded into the composer). */
    var editingMsg by mutableStateOf<WaMessage?>(null)
        private set

    fun setReply(m: WaMessage?) {
        editingMsg = null
        replyTo = m
    }

    fun startEdit(m: WaMessage) {
        if (!waCanEdit(m, nowSeconds())) return
        replyTo = null
        editingMsg = m
    }

    /** The chip the composer should show, if any. */
    fun composerChip(names: Map<String, String>): ComposerChip? {
        val e = editingMsg ?: return replyTo?.let {
            ComposerChip(
                editing = false,
                who = if (it.fromMe) "You" else (names[it.sender] ?: it.senderName.ifBlank { it.sender }),
                snippet = waCaptionOf(it),
            )
        }
        return ComposerChip(editing = true, who = "", snippet = e.text)
    }

    fun clearComposerContext() {
        replyTo = null
        editingMsg = null
    }

    /** React to [m] with [emoji] (empty removes). Optimistic with rollback; groups name the
     *  target's original sender, which the server requires. */
    fun react(m: WaMessage, emoji: String) {
        val chat = currentJid ?: return
        val before = reactions
        window = window.withReaction(m.id, "me", emoji, nowSeconds())
        viewModelScope.launch {
            // Groups must name the target's original sender; the web sends it whenever the row has one.
            val sender = if (waIsGroup(chat)) m.sender.ifBlank { null } else null
            when (val r = repo.react(chat, m.id, emoji, sender)) {
                is ApiResult.Err -> {
                    window = window.copy(reactions = before)
                    message = "Reaction failed: ${r.message}"
                }
                is ApiResult.Ok -> Unit
            }
        }
    }

    /** Delete [m] for everyone (revoke, own fresh messages) or for me. Optimistic with rollback. */
    fun removeMessage(m: WaMessage, everyone: Boolean) {
        val chat = currentJid ?: return
        val beforeMessages = messages
        val beforeReactions = reactions
        window = window.withRemoved(m.id, everyone)
        viewModelScope.launch {
            when (val r = repo.delete(chat, m.id, everyone)) {
                is ApiResult.Err -> {
                    window = window.copy(messages = beforeMessages, reactions = beforeReactions)
                    message = "Delete failed: ${r.message}"
                }
                is ApiResult.Ok -> Unit
            }
        }
    }

    /** Edit my own text message. Optimistic text + "edited" flag, rollback on failure. */
    fun editMessage(m: WaMessage, text: String) {
        val chat = currentJid ?: return
        if (text.isBlank() || !waCanEdit(m, nowSeconds())) return
        val before = messages
        window = window.withEdited(m.id, text)
        editingMsg = null
        viewModelScope.launch {
            when (val r = repo.edit(chat, m.id, text)) {
                is ApiResult.Err -> {
                    window = window.copy(messages = before)
                    editingMsg = m
                    message = "Edit failed: ${r.message}"
                }
                is ApiResult.Ok -> Unit
            }
        }
    }

    fun forward(m: WaMessage, to: String) {
        val chat = currentJid ?: return
        viewModelScope.launch {
            when (val r = repo.forward(chat, m.id, to)) {
                is ApiResult.Ok -> message = "Forwarded"
                is ApiResult.Err -> message = "Forward failed: ${r.message}"
            }
        }
    }

    /** Pin or unpin a chat in sys-mon's own list; WhatsApp and wacli never see it. */
    fun localPin(chat: WaChat, pinned: Boolean) {
        viewModelScope.launch {
            when (val r = repo.setLocalPin(chat.jid, pinned)) {
                is ApiResult.Ok -> {
                    chats = chats.map {
                        if (it.jid != chat.jid) it
                        else it.copy(localPin = pinned, pinned = if (pinned) true else it.waPinned)
                    }.let(::waSortChats)
                }
                is ApiResult.Err -> message = "Pin failed: ${r.message}"
            }
        }
    }

    /** Name a chat in sys-mon only (a blank name goes back to WhatsApp's); WhatsApp and wacli never see it. */
    fun renameChat(chat: WaChat, name: String) {
        viewModelScope.launch {
            when (val r = repo.setChatName(chat.jid, name)) {
                is ApiResult.Ok -> {
                    chats = chats.map { if (it.jid == chat.jid) it.withSysmonName(r.value) else it }
                    if (currentJid == chat.jid) chats.firstOrNull { it.jid == chat.jid }?.let { currentName = it.name }
                }
                is ApiResult.Err -> message = "Rename failed: ${r.message}"
            }
        }
    }

    private fun handleLive(msg: WaMessage) {
        if (msg.chat.isBlank() || msg.deletedForMe) return

        val applied = window.applyLive(msg, inOpenChat = msg.chat == currentJid)
        window = applied.window
        when (applied.effect) {
            // Reactions and in-place edits / deletes touch no row, unread count, preview or scroll-follow.
            LiveEffect.REACTION_FOLDED, LiveEffect.ROW_UPDATED -> return
            LiveEffect.APPENDED -> {
                mediaTools.prefetch(listOf(msg))
                liveTick++
            }
            LiveEffect.NONE -> Unit
        }
        updateChatListFor(msg)
    }

    /** Reorder the chat list and tally unread for a live message. */
    private fun updateChatListFor(msg: WaMessage) {
        val existing = chats.firstOrNull { it.jid == msg.chat }
        if (existing == null) {
            loadChats()
        } else {
            val bumpUnread = !msg.fromMe && msg.chat != currentJid
            chats = chats.map {
                if (it.jid == msg.chat) waPreviewFromMessage(it, msg).copy(
                    lastTs = maxOf(it.lastTs, msg.ts),
                    unread = if (bumpUnread) it.unread + 1 else it.unread,
                    name = if (it.name.isBlank() && msg.chatName.isNotBlank()) msg.chatName else it.name,
                ) else it
            }.let(::waSortChats)
        }
    }

    override fun onCleared() {
        voice.release()
        recorder.cancel()
        super.onCleared()
    }

    companion object {
        private const val AVATAR_LIMIT = 40
        private const val MAX_AVATAR_PASSES = 6
        /** A search hit opens the page starting this far before it, so a little context sits above it. */
        private const val SEEK_MARGIN_SEC = 60.0
        private const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024

        /** Composition edge: wires the screen's ViewModel to the app-wide [AppContainer]. */
        fun from(container: AppContainer): WhatsAppViewModel {
            val context = container.appContext
            return WhatsAppViewModel(
                repo = container.whatsapp,
                uploads = container.uploads,
                mediaTools = AndroidWhatsAppMediaTools(
                    context, container.imageLoader, container.api.okHttpClient, container.whatsapp,
                ),
                imageLoader = container.imageLoader,
                voiceFactory = { scope, onMessage ->
                    VoiceNotePlayer(context.cacheDir, container.api.okHttpClient, scope, onMessage)
                },
                recorderFactory = { scope, onMessage ->
                    VoiceRecorder(context.cacheDir, { newMediaRecorder(context) }, scope, onMessage)
                },
            )
        }
    }
}
