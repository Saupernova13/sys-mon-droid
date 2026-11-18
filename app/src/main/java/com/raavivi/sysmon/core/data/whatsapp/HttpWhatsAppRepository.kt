package com.raavivi.sysmon.core.data.whatsapp

import com.raavivi.sysmon.core.model.WaAvatarsRefreshReq
import com.raavivi.sysmon.core.model.WaAvatarsRefreshResponse
import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.model.WaContact
import com.raavivi.sysmon.core.model.WaDelete
import com.raavivi.sysmon.core.model.WaEdit
import com.raavivi.sysmon.core.model.WaForward
import com.raavivi.sysmon.core.model.WaLatestReq
import com.raavivi.sysmon.core.model.WaLocalPinReq
import com.raavivi.sysmon.core.model.WaMarkRead
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaPinReq
import com.raavivi.sysmon.core.model.WaReact
import com.raavivi.sysmon.core.model.WaSendText
import com.raavivi.sysmon.core.model.WaStatus
import com.raavivi.sysmon.core.model.WaTimelineDay
import com.raavivi.sysmon.core.model.toWaChat
import com.raavivi.sysmon.core.model.toWaContact
import com.raavivi.sysmon.core.model.toWaMessage
import com.raavivi.sysmon.core.net.ApiProvider
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
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
import okio.ByteString
import okio.source
import java.io.File
import java.io.IOException

/** [WhatsAppRepository] over the sys-mon REST API and its `/ws/whatsapp` socket. */
class HttpWhatsAppRepository(private val api: ApiProvider) : WhatsAppRepository {

    override suspend fun status(): ApiResult<WaStatus> = safeCall { api.api.waStatus() }

    override suspend fun chats(): ApiResult<List<WaChat>> =
        safeCall { api.api.waChats() }.mapOk { r -> r.chats.map { it.toWaChat() } }

    override suspend fun messages(
        chat: String,
        limit: Int,
        before: String?,
        after: String?,
        asc: Boolean?,
    ): ApiResult<List<WaMessage>> =
        safeCall { api.api.waMessages(chat = chat, limit = limit, before = before, after = after, asc = asc) }
            .mapOk { r -> r.messages.map { it.toWaMessage() } }

    override suspend fun timeline(chat: String): ApiResult<List<WaTimelineDay>> =
        safeCall { api.api.waTimeline(chat = chat) }.mapOk { it.days }

    override suspend fun searchContacts(query: String): ApiResult<List<WaContact>> =
        safeCall { api.api.waContacts(q = query) }.mapOk { r -> r.contacts.map { it.toWaContact() } }

    override suspend fun sendText(to: String, message: String, replyTo: String?): ApiResult<Unit> =
        safeCall { api.api.waSend(WaSendText(to = to, message = message, replyTo = replyTo)) }.mapOk { }

    override suspend fun sendMedia(to: String, upload: UploadFile, caption: String): ApiResult<Unit> {
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = upload.mimeType?.toMediaTypeOrNull()
            override fun contentLength(): Long = upload.size ?: -1L
            override fun writeTo(sink: BufferedSink) {
                val input = upload.open() ?: throw IOException("Cannot open ${upload.name}")
                input.use { sink.writeAll(it.source()) }
            }
        }
        return withContext(Dispatchers.IO) {
            safeCall { api.api.waSendMedia(to, upload.name, caption, body) }
        }.mapOk { }
    }

    override suspend fun sendVoice(to: String, replyTo: String?, audio: File): ApiResult<Unit> {
        val body = audio.asRequestBody("audio/mp4".toMediaType())
        return withContext(Dispatchers.IO) {
            safeCall { api.api.waSendVoice(to, replyTo, body) }
        }.mapOk { }
    }

    override suspend fun react(chat: String, id: String, emoji: String, sender: String?): ApiResult<Unit> =
        safeCall { api.api.waReact(WaReact(chat = chat, id = id, emoji = emoji, sender = sender)) }.mapOk { }

    override suspend fun forward(chat: String, id: String, to: String): ApiResult<Unit> =
        safeCall { api.api.waForward(WaForward(chat = chat, id = id, to = to)) }.mapOk { }

    override suspend fun delete(chat: String, id: String, everyone: Boolean): ApiResult<Unit> =
        safeCall { api.api.waDelete(WaDelete(chat = chat, id = id, everyone = everyone)) }.mapOk { }

    override suspend fun edit(chat: String, id: String, message: String): ApiResult<Unit> =
        safeCall { api.api.waEdit(WaEdit(chat = chat, id = id, message = message)) }.mapOk { }

    override suspend fun setPinned(chat: String, pinned: Boolean): ApiResult<Unit> =
        safeCall { api.api.waPin(WaPinReq(chat = chat, pinned = pinned)) }.mapOk { }

    override suspend fun setLocalPin(chat: String, pinned: Boolean): ApiResult<Unit> =
        safeCall { api.api.waLocalPin(WaLocalPinReq(chat = chat, pinned = pinned)) }.mapOk { }

    override suspend fun markRead(chat: String): ApiResult<Unit> =
        safeCall { api.api.waMarkRead(WaMarkRead(chat = chat)) }.mapOk { }

    override suspend fun fetchLatest(chat: String): ApiResult<Unit> =
        safeCall { api.api.waLatest(WaLatestReq(chat = chat)) }.mapOk { }

    override suspend fun refreshAvatars(jids: List<String>, limit: Int): ApiResult<WaAvatarsRefreshResponse> =
        safeCall { api.api.waAvatarsRefresh(WaAvatarsRefreshReq(jids = jids, limit = limit)) }

    override fun mediaUrl(chat: String, id: String, thumb: Boolean): String =
        api.whatsappMediaUrl(chat, id, thumb)

    override fun avatarUrl(jid: String): String = api.whatsappAvatarUrl(jid)

    override fun liveMessages(): Flow<WaMessage> = callbackFlow {
        val socket = api.openWebSocket("/ws/whatsapp", emptyMap(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { emit(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { emit(bytes.utf8()) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                close()
            }

            private fun emit(frame: String) { parseLiveFrame(frame)?.let { trySend(it) } }
        })
        awaitClose { socket.cancel() }
    }.buffer(Channel.UNLIMITED)
}

/** A live frame as a message, or null when it is not a JSON object. */
internal fun parseLiveFrame(text: String): WaMessage? =
    runCatching { SysMonJson.parseToJsonElement(text).jsonObject }.getOrNull()?.toWaMessage()

private inline fun <T, R> ApiResult<T>.mapOk(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(transform(value))
    is ApiResult.Err -> this
}
