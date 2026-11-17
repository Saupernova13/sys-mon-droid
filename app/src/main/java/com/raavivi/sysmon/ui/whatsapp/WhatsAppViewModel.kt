package com.raavivi.sysmon.ui.whatsapp

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.WaAvatarsRefreshReq
import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.model.WaLatestReq
import com.raavivi.sysmon.core.model.WaMarkRead
import com.raavivi.sysmon.core.model.WaContact
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaPinReq
import com.raavivi.sysmon.core.model.WaSendText
import com.raavivi.sysmon.core.model.WaStatus
import com.raavivi.sysmon.core.model.toWaChat
import com.raavivi.sysmon.core.model.toWaContact
import com.raavivi.sysmon.core.model.toWaMessage
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.core.net.safeCall
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.source
import java.io.File

class WhatsAppViewModel(private val container: AppContainer) : ViewModel() {

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
    var messages by mutableStateOf<List<WaMessage>>(emptyList())
        private set
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

    /** Whether the loaded window includes the chat's newest message; false after jumping to the first page. */
    var atLatest by mutableStateOf(true)
        private set

    private var loadingOlder = false
    private var loadingNewer = false

    /** The oldest message is loaded, so there is nothing older to request. */
    private var reachedFirst = false

    /** Bumped whenever the window is replaced, so a page still in flight for the old window is dropped. */
    private var windowEpoch = 0

    /** Display name of the file currently being uploaded, or null when idle. */
    var uploading by mutableStateOf<String?>(null)
        private set

    var contacts by mutableStateOf<List<WaContact>>(emptyList())
        private set

    val imageLoader: ImageLoader = container.imageLoader

    /** Per-jid counter folded into the avatar's cache key; bumped when a refresh cached a new picture. */
    var avatarVersions by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    /** Jids already offered to the server's avatar refresh this session. */
    private val avatarTried = mutableSetOf<String>()

    val voice = VoiceNotePlayer(
        cacheDir = container.appContext.cacheDir,
        client = container.api.okHttpClient,
        scope = viewModelScope,
        onMessage = { message = it },
    )

    val recorder = VoiceRecorder(
        context = container.appContext,
        scope = viewModelScope,
        onMessage = { message = it },
    )

    private val frames = Channel<String>(Channel.UNLIMITED)
    private var ws: WebSocket? = null

    init {
        loadStatus()
        loadChats()
        viewModelScope.launch { for (f in frames) handleLive(f) }
        connectWs()
    }

    fun mediaUrl(msg: WaMessage): String = container.api.whatsappMediaUrl(msg.chat, msg.id)

    fun avatarUrl(jid: String): String = container.api.whatsappAvatarUrl(jid)

    fun loadStatus() {
        viewModelScope.launch {
            (safeCall { container.api.api.waStatus() } as? ApiResult.Ok)?.let { status = it.value }
        }
    }

    fun loadChats() {
        loadingChats = true
        error = null
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.waChats() }) {
                is ApiResult.Ok -> chats = r.value.chats.map { it.toWaChat() }.sortedWith(chatOrder)
                is ApiResult.Err -> error = r.message
            }
            loadingChats = false
            refreshAvatars()
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
            val r = safeCall {
                container.api.api.waAvatarsRefresh(WaAvatarsRefreshReq(jids = pending.toList(), limit = AVATAR_LIMIT))
            }
            val ok = (r as? ApiResult.Ok)?.value ?: return
            if (ok.updated.isNotEmpty()) {
                avatarVersions = avatarVersions + ok.updated.associateWith { (avatarVersions[it] ?: 0) + 1 }
                pending.removeAll(ok.updated.toSet())
            }
            if (ok.attempted < ok.limit) return
        }
    }

    fun openChat(jid: String, name: String) {
        currentJid = jid
        currentName = name
        currentPinned = chats.firstOrNull { it.jid == jid }?.pinned ?: false
        resetWindow()
        voice.release()
        recorder.cancel()
        timeline = null
        loadMessages(jid)
        viewModelScope.launch {
            val r = safeCall { container.api.api.waTimeline(chat = jid) }
            if (currentJid != jid) return@launch
            // A failure leaves the timeline null: the scrubber and date picker just stay hidden.
            if (r is ApiResult.Ok) timeline = buildTimeline(r.value.days)
        }
    }

    /** Whether the open chat has unread messages (enables "Mark as read"). */
    val currentUnread: Int get() = chats.firstOrNull { it.jid == currentJid }?.unread ?: 0

    /** The server no longer marks a chat read on its own; this does it explicitly. */
    fun markRead() {
        val jid = currentJid ?: return
        val cleared = currentUnread
        if (cleared <= 0) return
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.waMarkRead(WaMarkRead(chat = jid)) }) {
                is ApiResult.Ok -> {
                    chats = chats.map { if (it.jid == jid) it.copy(unread = 0) else it }
                    status = status?.let { it.copy(unreadTotal = (it.unreadTotal - cleared).coerceAtLeast(0)) }
                }
                is ApiResult.Err -> message = "Mark read failed: ${r.message}"
            }
        }
    }

    /** Download an image message and store it in the public Pictures collection. */
    fun saveImage(msg: WaMessage) {
        viewModelScope.launch {
            message = "Saving..."
            message = withContext(Dispatchers.IO) {
                runCatching {
                    val req = okhttp3.Request.Builder().url(mediaUrl(msg)).build()
                    container.api.okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val mime = resp.header("Content-Type")?.substringBefore(';')?.trim()
                        val body = resp.body ?: error("empty response")
                        val name = MediaSaver.fileName(msg, mime)
                        val where = MediaSaver.save(container.appContext, name, mime, body.byteStream())
                        "Saved to $where"
                    }
                }.getOrElse { "Save failed: ${it.message ?: "unknown error"}" }
            }
        }
    }

    fun closeChat() {
        voice.release()
        recorder.cancel()
        currentJid = null
        messages = emptyList()
        timeline = null
        resetWindow()
    }

    /** Back to "newest page, nothing in flight" when a conversation opens or closes. */
    private fun resetWindow() {
        windowEpoch++
        atLatest = true
        reachedFirst = false
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
        val r = safeCall { container.api.api.waMessages(chat = jid, limit = WA_PAGE_LIMIT) }
        if (currentJid != jid) return false
        if (merge && windowEpoch != epoch) return false
        return when (r) {
            is ApiResult.Ok -> {
                val page = r.value.messages.map { it.toWaMessage() }
                if (merge) {
                    messages = mergeMessages(messages, page).first
                } else {
                    windowEpoch++
                    messages = page.sortedBy { it.ts }
                    reachedFirst = false
                }
                atLatest = true
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
                val r = safeCall { container.api.api.waLatest(WaLatestReq(chat = jid)) }
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
                val r = safeCall {
                    container.api.api.waMessages(chat = jid, limit = WA_PAGE_LIMIT, asc = true)
                }
                if (currentJid != jid) return@launch
                when (r) {
                    is ApiResult.Ok -> {
                        val page = r.value.messages.map { it.toWaMessage() }
                        windowEpoch++
                        messages = page.sortedBy { it.ts }
                        atLatest = page.size < WA_PAGE_LIMIT
                        reachedFirst = true
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
                val r = safeCall {
                    container.api.api.waMessages(
                        chat = jid, limit = WA_PAGE_LIMIT, after = waInclusiveAfter(ts.toDouble()), asc = true,
                    )
                }
                if (currentJid != jid) return@launch
                when (r) {
                    is ApiResult.Ok -> {
                        val page = r.value.messages.map { it.toWaMessage() }
                        windowEpoch++
                        messages = page.sortedBy { it.ts }
                        atLatest = page.size < WA_PAGE_LIMIT
                        reachedFirst = timeline?.let { ts <= it.first.firstTs } ?: false
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
        if (reachedFirst) { if (!silent) message = "No older messages"; return }
        val oldest = messages.firstOrNull()?.takeIf { it.ts > 0 }?.ts ?: return
        val epoch = windowEpoch
        loadingOlder = true
        viewModelScope.launch {
            try {
                var strict = false
                while (true) {
                    val before = if (strict) waBeforeCursor(oldest) else waInclusiveBefore(oldest)
                    val r = safeCall {
                        container.api.api.waMessages(chat = jid, limit = WA_PAGE_LIMIT, before = before)
                    }
                    if (currentJid != jid || windowEpoch != epoch) return@launch
                    if (r !is ApiResult.Ok) return@launch
                    val page = r.value.messages.map { it.toWaMessage() }
                    val (merged, added) = mergeMessages(messages, page)
                    messages = merged
                    var outcome = pageOutcome(page.size, added, WA_PAGE_LIMIT)
                    if (outcome == PageOutcome.STALLED && !strict) {
                        strict = true
                        continue
                    }
                    if (outcome == PageOutcome.STALLED) outcome = PageOutcome.END
                    if (outcome == PageOutcome.END) {
                        reachedFirst = true
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
        val newest = messages.lastOrNull()?.takeIf { it.ts > 0 }?.ts ?: return
        val epoch = windowEpoch
        loadingNewer = true
        viewModelScope.launch {
            try {
                var strict = false
                while (true) {
                    // Strict `after` is the floor of the second: wacli then returns only later seconds.
                    val after = if (strict) waBeforeCursor(newest) else waInclusiveAfter(newest)
                    val r = safeCall {
                        container.api.api.waMessages(chat = jid, limit = WA_PAGE_LIMIT, after = after, asc = true)
                    }
                    if (currentJid != jid || windowEpoch != epoch) return@launch
                    if (r !is ApiResult.Ok) return@launch
                    val page = r.value.messages.map { it.toWaMessage() }
                    val (merged, added) = mergeMessages(messages, page)
                    messages = merged
                    var outcome = pageOutcome(page.size, added, WA_PAGE_LIMIT)
                    if (outcome == PageOutcome.STALLED && !strict) {
                        strict = true
                        continue
                    }
                    if (outcome == PageOutcome.STALLED) outcome = PageOutcome.END
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
        sending = true
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.waSend(WaSendText(to = jid, message = text)) }) {
                is ApiResult.Ok -> loadMessages(jid)
                is ApiResult.Err -> message = "Send failed: ${r.message}"
            }
            sending = false
        }
    }

    /** Display name of a picked document, for the confirm dialog. */
    fun displayName(uri: Uri): String = queryOpenable(uri).first ?: DEFAULT_UPLOAD_NAME

    /**
     * Upload a picked file to the open chat. The body is streamed straight from
     * the content resolver so a large file never sits in memory.
     */
    fun sendMedia(uri: Uri, caption: String) {
        val jid = currentJid ?: return
        if (sending || uploading != null) return
        val resolver = container.appContext.contentResolver
        sending = true
        viewModelScope.launch {
            val (name, size) = withContext(Dispatchers.IO) { queryOpenable(uri) }
            val filename = name ?: DEFAULT_UPLOAD_NAME
            if (size != null && size > MAX_UPLOAD_BYTES) {
                message = "Send failed: $filename is larger than 100 MiB"
                sending = false
                return@launch
            }
            uploading = "Uploading $filename..."
            val body = object : RequestBody() {
                override fun contentType(): MediaType? = resolver.getType(uri)?.toMediaTypeOrNull()
                override fun contentLength(): Long = size ?: -1L
                override fun writeTo(sink: BufferedSink) {
                    val input = resolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open $filename")
                    input.use { sink.writeAll(it.source()) }
                }
            }
            val r = withContext(Dispatchers.IO) {
                safeCall { container.api.api.waSendMedia(jid, filename, caption, body) }
            }
            when (r) {
                is ApiResult.Ok -> loadMessages(jid)
                is ApiResult.Err -> message = "Send failed: ${r.message}"
            }
            uploading = null
            sending = false
        }
    }

    /** Stop the recording and send it as a voice note; a too-short recording is discarded. */
    fun sendRecording() {
        val file = recorder.finish() ?: return
        sendVoice(file, null)
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
            val body = file.asRequestBody("audio/mp4".toMediaType())
            val r = withContext(Dispatchers.IO) {
                safeCall { container.api.api.waSendVoice(jid, replyTo, body) }
            }
            when (r) {
                is ApiResult.Ok -> loadMessages(jid)
                is ApiResult.Err -> message = "Send failed: ${r.message}"
            }
            file.delete()
            uploading = null
            sending = false
        }
    }

    /** Display name and size in bytes of a content Uri; either may be unknown. */
    private fun queryOpenable(uri: Uri): Pair<String?, Long?> {
        var name: String? = null
        var size: Long? = null
        runCatching {
            container.appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
        }
        return name to size
    }

    fun togglePin() {
        val jid = currentJid ?: return
        val next = !currentPinned
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.waPin(WaPinReq(chat = jid, pinned = next)) }) {
                is ApiResult.Ok -> {
                    currentPinned = next
                    chats = chats.map { if (it.jid == jid) it.copy(pinned = next) else it }.sortedWith(chatOrder)
                }
                is ApiResult.Err -> message = "Pin failed: ${r.message}"
            }
        }
    }

    fun searchContacts(q: String) {
        if (q.isBlank()) { contacts = emptyList(); return }
        viewModelScope.launch {
            (safeCall { container.api.api.waContacts(q = q) } as? ApiResult.Ok)?.let {
                contacts = it.value.contacts.map { c -> c.toWaContact() }
            }
        }
    }

    private fun connectWs() {
        ws?.cancel()
        ws = container.api.openWebSocket("/ws/whatsapp", emptyMap(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { frames.trySend(text) }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { frames.trySend(bytes.utf8()) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {}
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        })
    }

    private fun handleLive(text: String) {
        val obj = runCatching { SysMonJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val msg = obj.toWaMessage()
        if (msg.chat.isBlank()) return

        // Append to the open conversation, but only while the window reaches the newest message;
        // otherwise loadNewer / the final merge picks it up in order.
        if (msg.chat == currentJid && atLatest && messages.none { it.id == msg.id && it.id.isNotBlank() }) {
            messages = (messages + msg).sortedBy { it.ts }
            liveTick++
        }

        // Update the chat list ordering / unread tally.
        val existing = chats.firstOrNull { it.jid == msg.chat }
        if (existing == null) {
            loadChats()
        } else {
            val bumpUnread = !msg.fromMe && msg.chat != currentJid
            chats = chats.map {
                if (it.jid == msg.chat) it.copy(
                    lastTs = maxOf(it.lastTs, msg.ts),
                    unread = if (bumpUnread) it.unread + 1 else it.unread,
                    name = if (it.name.isBlank() && msg.chatName.isNotBlank()) msg.chatName else it.name,
                ) else it
            }.sortedWith(chatOrder)
        }
    }

    override fun onCleared() {
        voice.release()
        recorder.cancel()
        ws?.cancel()
        frames.close()
        super.onCleared()
    }

    private companion object {
        const val DEFAULT_UPLOAD_NAME = "upload.bin"
        const val AVATAR_LIMIT = 40
        const val MAX_AVATAR_PASSES = 6
        const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024
        val chatOrder = compareByDescending<WaChat> { it.pinned }.thenByDescending { it.lastTs }
    }
}
