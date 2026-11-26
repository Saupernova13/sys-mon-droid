package com.raavivi.sysmon.core.data.whatsapp

import com.raavivi.sysmon.core.model.WaAvatarsRefreshReq
import com.raavivi.sysmon.core.model.WaAvatarsRefreshResponse
import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.model.WaCardDetail
import com.raavivi.sysmon.core.model.WaCardSettings
import com.raavivi.sysmon.core.model.WaCardSettingsPatch
import com.raavivi.sysmon.core.model.WaChatSearchResponse
import com.raavivi.sysmon.core.model.WaChatSearchStatus
import com.raavivi.sysmon.core.model.WaContact
import com.raavivi.sysmon.core.model.WaDelete
import com.raavivi.sysmon.core.model.WaEdit
import com.raavivi.sysmon.core.model.WaForward
import com.raavivi.sysmon.core.model.WaLatestReq
import com.raavivi.sysmon.core.model.WaLocalPinReq
import com.raavivi.sysmon.core.model.WaChatNameReq
import com.raavivi.sysmon.core.model.WaManualCardReq
import com.raavivi.sysmon.core.model.WaManualJob
import com.raavivi.sysmon.core.model.WaMarkRead
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaPickCounts
import com.raavivi.sysmon.core.model.WaPickReq
import com.raavivi.sysmon.core.model.WaTagSuggestResponse
import com.raavivi.sysmon.core.model.WaTagsReq
import com.raavivi.sysmon.core.model.WaTagsResponse
import com.raavivi.sysmon.core.model.WaPinReq
import com.raavivi.sysmon.core.model.WaReact
import com.raavivi.sysmon.core.model.WaSearchResponse
import com.raavivi.sysmon.core.model.WaSendText
import com.raavivi.sysmon.core.model.WaStatus
import com.raavivi.sysmon.core.model.WaTimelineDay
import com.raavivi.sysmon.core.model.toWaChat
import com.raavivi.sysmon.core.model.toWaContact
import com.raavivi.sysmon.core.model.toWaMessage
import com.raavivi.sysmon.core.net.ApiProvider
import com.raavivi.sysmon.core.log.AppLog
import com.raavivi.sysmon.core.model.ErrorEnvelope
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.core.net.parseHttpError
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
import retrofit2.HttpException
import retrofit2.Response as RetrofitResponse
import java.io.File
import java.io.IOException

/** [WhatsAppRepository] over the sys-mon REST API and its `/ws/whatsapp` socket. */
class HttpWhatsAppRepository(private val api: ApiProvider) : WhatsAppRepository {

    override suspend fun status(): ApiResult<WaStatus> = safeCall { api.api.waStatus() }

    override suspend fun chats(limit: Int): ApiResult<List<WaChat>> =
        safeCall { api.api.waChats(limit) }.mapOk { r -> r.chats.map { it.toWaChat() } }

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

    override suspend fun setChatName(chat: String, name: String): ApiResult<String?> =
        safeCall { api.api.waChatName(WaChatNameReq(chat = chat, name = name)) }.mapOk { it.name }

    override suspend fun markRead(chat: String): ApiResult<Unit> =
        safeCall { api.api.waMarkRead(WaMarkRead(chat = chat)) }.mapOk { }

    override suspend fun fetchLatest(chat: String): ApiResult<Unit> =
        safeCall { api.api.waLatest(WaLatestReq(chat = chat)) }.mapOk { }

    override suspend fun refreshAvatars(jids: List<String>, limit: Int): ApiResult<WaAvatarsRefreshResponse> =
        safeCall { api.api.waAvatarsRefresh(WaAvatarsRefreshReq(jids = jids, limit = limit)) }

    override suspend fun search(
        q: String,
        chat: String?,
        fromMe: Boolean?,
        media: String?,
        limit: Int,
        offset: Int?,
    ): ApiResult<WaSearchResponse> =
        safeCall { api.api.waSearch(q = q, chat = chat, fromMe = fromMe, media = media, limit = limit, offset = offset) }

    override suspend fun searchDays(q: String, chat: String): ApiResult<List<WaTimelineDay>> =
        safeCall { api.api.waSearchDays(q = q, chat = chat) }.mapOk { it.days }

    override suspend fun chatSearchStatus(): ApiResult<WaChatSearchStatus> = safeCall { api.api.waChatSearchStatus() }

    override suspend fun chatSearchIndex(): ApiResult<Unit> = safeCall { api.api.waChatSearchIndex() }.mapOk { }

    override suspend fun chatSearchCardsRun(): ApiResult<Unit> = safeCall { api.api.waChatSearchCardsRun() }.mapOk { }

    override suspend fun chatSearchCardsStop(): ApiResult<Unit> = safeCall { api.api.waChatSearchCardsStop() }.mapOk { }

    override suspend fun cardSettings(probe: String?): ApiResult<WaCardSettings> =
        safeResponse { api.api.waCardSettings(probe?.takeIf { it.isNotBlank() }) }

    override suspend fun saveCardSettings(patch: WaCardSettingsPatch): ApiResult<WaCardSettings> =
        safeResponse { api.api.waCardSettingsSave(patch) }

    override suspend fun chatSearch(q: String, limit: Int): ApiResult<WaChatSearchResponse> =
        when (val r = safeCall { api.api.waChatSearch(q = q, limit = limit) }) {
            is ApiResult.Ok -> chatSearchResult(r.value)
            is ApiResult.Err -> r
        }

    override suspend fun chatSearchPick(body: WaPickReq): ApiResult<WaPickCounts> =
        safeCall { api.api.waChatSearchPick(body) }

    override suspend fun chatSearchCard(node: Long): ApiResult<WaCardDetail> =
        safeCall { api.api.waChatSearchCard(node) }

    override suspend fun chatSearchCardById(card: Long): ApiResult<WaCardDetail> =
        safeCall { api.api.waChatSearchCardById(card) }

    override suspend fun createManualCard(chat: String, msgIds: List<String>, instruction: String?): ApiResult<WaManualJob> =
        safeCall { api.api.waManualCardCreate(WaManualCardReq(chat = chat, msgIds = msgIds, instruction = instruction)) }
            .mapOk { it.job }

    override suspend fun manualJob(id: Long): ApiResult<WaManualJob> = safeCall { api.api.waManualCardJob(id) }

    override suspend fun cancelManualJob(id: Long): ApiResult<Unit> =
        when (val r = safeCall { api.api.waManualCardCancel(id) }) {
            is ApiResult.Ok -> emptyResult(r.value)
            is ApiResult.Err -> r
        }

    override suspend fun deleteCard(id: Long): ApiResult<Unit> =
        when (val r = safeCall { api.api.waCardDelete(id) }) {
            is ApiResult.Ok -> emptyResult(r.value)
            is ApiResult.Err -> r
        }

    override suspend fun chatSearchTags(body: WaTagsReq): ApiResult<WaTagsResponse> =
        safeCall { api.api.waChatSearchTags(body) }

    override suspend fun chatSearchTagSuggest(cat: String, q: String, limit: Int): ApiResult<WaTagSuggestResponse> =
        safeCall { api.api.waChatSearchTagSuggest(cat.ifBlank { null }, q.ifBlank { null }, limit) }

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

/**
 * A conversation-search reply as a result. The server answers a word-only search with a 503 that still
 * carries the full body (`"degraded": "keyword"`); that is usable, so it is Ok. Any other failure is an Err
 * carrying the server's message (about the user's input, never the query). The body is read once.
 */
internal fun chatSearchResult(r: RetrofitResponse<WaChatSearchResponse>): ApiResult<WaChatSearchResponse> {
    if (r.isSuccessful) return r.body()?.let { ApiResult.Ok(it) } ?: ApiResult.Err("Empty response", r.code())
    val raw = runCatching { r.errorBody()?.string() }.getOrNull()?.takeIf { it.isNotBlank() }
    if (r.code() == 503 && raw != null) {
        val body = runCatching { SysMonJson.decodeFromString(WaChatSearchResponse.serializer(), raw) }.getOrNull()
        if (body?.degraded != null) return ApiResult.Ok(body)
    }
    val envelope = raw?.let { runCatching { SysMonJson.decodeFromString(ErrorEnvelope.serializer(), it) }.getOrNull() }
    AppLog.w("Api", "error code=${r.code()} apiCode=${envelope?.code}")
    return ApiResult.Err(parseHttpError(HttpException(r), envelope), r.code(), envelope?.code)
}

/** Runs a call that answers a [RetrofitResponse], so a failure's body is read here ([cardSettingsError]). */
private suspend fun <T> safeResponse(block: suspend () -> RetrofitResponse<T>): ApiResult<T> =
    when (val r = safeCall { block() }) {
        is ApiResult.Err -> r
        is ApiResult.Ok -> cardSettingsResult(r.value)
    }

/** A 2xx body as Ok; else Err with the HTTP status and [cardSettingsError] of the body. */
internal fun <T> cardSettingsResult(r: RetrofitResponse<T>): ApiResult<T> {
    if (r.isSuccessful) return r.body()?.let { ApiResult.Ok(it) } ?: ApiResult.Err("Empty response", r.code())
    val raw = runCatching { r.errorBody()?.string() }.getOrNull()
    AppLog.w("Api", "error code=${r.code()}")
    return ApiResult.Err(cardSettingsError(r.code(), raw), r.code())
}

/**
 * The words of a failed card-settings call. The server's 400 body is a plain reason string; a `{error}` envelope
 * is read too. Anything else gets the generic text.
 */
internal fun cardSettingsError(code: Int, raw: String?): String {
    val text = raw?.trim().orEmpty()
    val envelope = text.takeIf { it.startsWith("{") }
        ?.let { runCatching { SysMonJson.decodeFromString(ErrorEnvelope.serializer(), it) }.getOrNull() }
    val msg = (envelope?.error ?: envelope?.detail)?.takeIf { it.isNotBlank() }
        ?: text.takeIf { envelope == null && it.isNotEmpty() && it.length <= 200 && !it.startsWith("<") && !it.startsWith("{") }
    return when {
        code == 401 -> "Not authorised — please log in again"
        code == 404 -> "Chat search is off"
        msg != null -> msg
        else -> "HTTP $code"
    }
}
