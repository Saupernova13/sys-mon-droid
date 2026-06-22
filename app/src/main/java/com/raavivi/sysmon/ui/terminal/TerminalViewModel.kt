package com.raavivi.sysmon.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.core.term.TerminalEmulator
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

enum class TermState { Connecting, Connected, Disconnected }

class TerminalViewModel(private val container: AppContainer) : ViewModel() {

    @Serializable
    private data class TermSessionMsg(val type: String? = null, val id: String? = null, val title: String? = null)

    var state by mutableStateOf(TermState.Connecting)
        private set
    var title by mutableStateOf("Terminal")
        private set
    var lines by mutableStateOf<List<List<TerminalEmulator.TermSpan>>>(emptyList())
        private set

    private val emulator = TerminalEmulator(cols = 80, rows = 24)
    private val frames = Channel<String>(Channel.UNLIMITED)
    private var ws: WebSocket? = null

    private var sessionId: String? = null
    private var sessionCaptured = false
    private var dirty = false

    init {
        // Consume PTY frames in order on a single coroutine; render is throttled.
        viewModelScope.launch {
            for (s in frames) handleFrame(s)
        }
        viewModelScope.launch {
            while (isActive) {
                if (dirty) { dirty = false; lines = emulator.render() }
                kotlinx.coroutines.delay(40)
            }
        }
        connect()
    }

    fun connect() {
        ws?.cancel()
        state = TermState.Connecting
        viewModelScope.launch {
            val sid = container.settings.terminalSessionNow()
            sessionId = sid
            sessionCaptured = sid != null
            val extra = if (sid != null) mapOf("session_id" to sid) else emptyMap()
            ws = container.api.openWebSocket("/ws/terminal", extra, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    post { state = TermState.Connected; sendResize() }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames.trySend(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    frames.trySend(bytes.utf8())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    post { state = TermState.Disconnected }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    post { state = TermState.Disconnected }
                }
            })
        }
    }

    private fun handleFrame(s: String) {
        if (s.isEmpty()) return // keepalive ping
        if (!sessionCaptured && s.startsWith("{") && s.contains("\"type\"")) {
            val msg = runCatching { SysMonJson.decodeFromString(TermSessionMsg.serializer(), s) }.getOrNull()
            if (msg?.type == "session" && msg.id != null) {
                sessionId = msg.id
                sessionCaptured = true
                title = msg.title ?: title
                viewModelScope.launch { container.settings.setTerminalSession(msg.id) }
                return
            }
        }
        emulator.feed(s)
        dirty = true
    }

    /** Send a UTF-8 keystroke payload as a binary frame (raw PTY input). */
    fun sendInput(data: String) {
        if (data.isEmpty()) return
        ws?.send(data.encodeUtf8())
    }

    /** Update the emulated grid + tell the PTY its new size. */
    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        if (newCols == emulator.cols && newRows == emulator.rows) return
        emulator.resize(newRows, newCols)
        dirty = true
        sendResize()
    }

    private fun sendResize() {
        ws?.send("""{"type":"resize","rows":${emulator.rows},"cols":${emulator.cols}}""")
    }

    private inline fun post(crossinline block: () -> Unit) {
        viewModelScope.launch { block() }
    }

    override fun onCleared() {
        ws?.cancel()
        frames.close()
        super.onCleared()
    }
}
