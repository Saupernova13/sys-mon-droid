package com.raavivi.sysmon.core.net

import com.raavivi.sysmon.core.log.AppLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.DeserializationStrategy
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Bridges an OkHttp text WebSocket into a cold [Flow] of decoded JSON frames.
 *
 * The flow completes (closes) on socket failure or remote close so the collector
 * can decide whether to reconnect (typically with `retryWhen`). Frames that fail
 * to decode are dropped rather than killing the stream. Binary frames are inflated
 * by a per-socket [FrameDecoder] (the server sends them when asked with `z=1`);
 * text frames decode as before, so an older server still works.
 */
fun <T> ApiProvider.jsonWebSocketFlow(
    path: String,
    deserializer: DeserializationStrategy<T>,
    extraQuery: Map<String, String> = emptyMap(),
): Flow<T> = callbackFlow {
    val frames = FrameDecoder()
    val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            AppLog.i("Ws", "open $path")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            emit(frames.text(text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            frames.binary(bytes.toByteArray())?.let { emit(it) }
        }

        private fun emit(json: String) {
            val decoded = runCatching { SysMonJson.decodeFromString(deserializer, json) }.getOrNull()
            if (decoded != null) trySend(decoded)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            AppLog.w("Ws", "failure $path ${t.javaClass.simpleName}: ${t.message}")
            close(t)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            AppLog.i("Ws", "closing $path code=$code")
            webSocket.close(code, null)
            close()
        }
    }

    val socket = openWebSocket(path, extraQuery, listener)
    awaitClose {
        socket.cancel()
        frames.close()
    }
}
