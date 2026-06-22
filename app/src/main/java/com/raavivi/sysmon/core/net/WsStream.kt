package com.raavivi.sysmon.core.net

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
 * to decode are dropped rather than killing the stream.
 */
fun <T> ApiProvider.jsonWebSocketFlow(
    path: String,
    deserializer: DeserializationStrategy<T>,
    extraQuery: Map<String, String> = emptyMap(),
): Flow<T> = callbackFlow {
    val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val decoded = runCatching { SysMonJson.decodeFromString(deserializer, text) }.getOrNull()
            if (decoded != null) trySend(decoded)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // JSON streams only send text frames; ignore any binary.
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            close(t)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            close()
        }
    }

    val socket = openWebSocket(path, extraQuery, listener)
    awaitClose { socket.cancel() }
}
