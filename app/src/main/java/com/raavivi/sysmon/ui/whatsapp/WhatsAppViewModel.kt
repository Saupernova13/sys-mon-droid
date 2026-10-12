package com.raavivi.sysmon.ui.whatsapp

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.WaChat
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.source

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

    /** Display name of the file currently being uploaded, or null when idle. */
    var uploading by mutableStateOf<String?>(null)
        private set

    var contacts by mutableStateOf<List<WaContact>>(emptyList())
        private set

    val imageLoader: ImageLoader = container.imageLoader

    private val frames = Channel<String>(Channel.UNLIMITED)
    private var ws: WebSocket? = null

    init {
        loadStatus()
        loadChats()
        viewModelScope.launch { for (f in frames) handleLive(f) }
        connectWs()
    }

    fun mediaUrl(msg: WaMessage): String = container.api.whatsappMediaUrl(msg.chat, msg.id)

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
        }
    }

    fun openChat(jid: String, name: String) {
        currentJid = jid
        currentName = name
        currentPinned = chats.firstOrNull { it.jid == jid }?.pinned ?: false
        loadMessages(jid)
        // Opening clears the local unread badge (server does the same).
        chats = chats.map { if (it.jid == jid) it.copy(unread = 0) else it }
    }

    fun closeChat() {
        currentJid = null
        messages = emptyList()
    }

    private fun loadMessages(jid: String) {
        loadingMessages = true
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.waMessages(chat = jid, limit = 50) }) {
                is ApiResult.Ok -> messages = r.value.messages.map { it.toWaMessage() }.sortedBy { it.ts }
                is ApiResult.Err -> { error = r.message }
            }
            loadingMessages = false
        }
    }

    fun loadOlder() {
        val jid = currentJid ?: return
        val oldest = messages.firstOrNull()?.id ?: return
        viewModelScope.launch {
            val r = safeCall { container.api.api.waMessages(chat = jid, limit = 50, before = oldest) }
            if (r is ApiResult.Ok) {
                val older = r.value.messages.map { it.toWaMessage() }
                val merged = (older + messages).distinctBy { it.id }.sortedBy { it.ts }
                messages = merged
                if (older.isEmpty()) message = "No older messages"
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
            uploading = filename
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

        // Append to the open conversation.
        if (msg.chat == currentJid && messages.none { it.id == msg.id && it.id.isNotBlank() }) {
            messages = (messages + msg).sortedBy { it.ts }
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
        ws?.cancel()
        frames.close()
        super.onCleared()
    }

    private companion object {
        const val DEFAULT_UPLOAD_NAME = "upload.bin"
        const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024
        val chatOrder = compareByDescending<WaChat> { it.pinned }.thenByDescending { it.lastTs }
    }
}
