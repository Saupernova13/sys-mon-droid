package com.raavivi.sysmon.ui.screen

import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.ScreenEvent
import com.raavivi.sysmon.core.net.SysMonJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

enum class ScreenState { Connecting, Connected, Disconnected }

class ScreenShareViewModel(private val container: AppContainer) : ViewModel() {

    var state by mutableStateOf(ScreenState.Connecting)
        private set
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var frameWidth by mutableStateOf(0)
        private set
    var frameHeight by mutableStateOf(0)
        private set

    private var ws: WebSocket? = null

    init { connect() }

    fun connect() {
        ws?.cancel()
        state = ScreenState.Connecting
        ws = container.api.openWebSocket("/ws/screen", emptyMap(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                post { state = ScreenState.Connected }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val arr = bytes.toByteArray()
                val bmp = runCatching { BitmapFactory.decodeByteArray(arr, 0, arr.size) }.getOrNull() ?: return
                post {
                    frameWidth = bmp.width
                    frameHeight = bmp.height
                    frame = bmp.asImageBitmap()
                    if (state != ScreenState.Connected) state = ScreenState.Connected
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                post { state = ScreenState.Disconnected }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                post { state = ScreenState.Disconnected }
            }
        })
    }

    private fun send(event: ScreenEvent) {
        val w = ws ?: return
        val json = runCatching { SysMonJson.encodeToString(ScreenEvent.serializer(), event) }.getOrNull() ?: return
        w.send(json)
    }

    fun moveTo(x: Float, y: Float) = send(ScreenEvent("mousemove", x = x, y = y))

    fun click(x: Float, y: Float, button: String = "left") {
        send(ScreenEvent("mousedown", x = x, y = y, button = button))
        send(ScreenEvent("mouseup", x = x, y = y, button = button))
    }

    fun mouseDown(x: Float, y: Float, button: String = "left") =
        send(ScreenEvent("mousedown", x = x, y = y, button = button))

    fun mouseUp(x: Float, y: Float, button: String = "left") =
        send(ScreenEvent("mouseup", x = x, y = y, button = button))

    fun scroll(x: Float, y: Float, dy: Float) = send(ScreenEvent("scroll", x = x, y = y, dy = dy))

    /** Type one key by name (printable char or KEY_MAP name like "Enter"/"Backspace"). */
    fun pressKey(key: String) {
        send(ScreenEvent("keydown", key = key))
        send(ScreenEvent("keyup", key = key))
    }

    fun typeString(s: String) {
        for (ch in s) pressKey(ch.toString())
    }

    private inline fun post(crossinline block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) { block() }
    }

    override fun onCleared() {
        ws?.cancel()
        super.onCleared()
    }
}
