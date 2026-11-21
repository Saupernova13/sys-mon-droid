package com.raavivi.sysmon

import com.raavivi.sysmon.core.data.whatsapp.UploadFile
import com.raavivi.sysmon.core.data.whatsapp.UploadSource
import com.raavivi.sysmon.core.data.whatsapp.WhatsAppRepository
import com.raavivi.sysmon.core.model.WaAvatarsRefreshResponse
import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.model.WaChatSearchResponse
import com.raavivi.sysmon.core.model.WaChatSearchStatus
import com.raavivi.sysmon.core.model.WaContact
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaPickCounts
import com.raavivi.sysmon.core.model.WaPickReq
import com.raavivi.sysmon.core.model.WaSearchResponse
import com.raavivi.sysmon.core.model.WaStatus
import com.raavivi.sysmon.core.model.WaTimelineDay
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.ui.whatsapp.WhatsAppMediaTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File

/** One recorded `messages` request. */
data class MessagesCall(val chat: String, val limit: Int, val before: String?, val after: String?, val asc: Boolean?)

data class SentText(val to: String, val message: String, val replyTo: String?)
data class SentMedia(val to: String, val name: String, val caption: String)
data class SentVoice(val to: String, val replyTo: String?, val audio: File)
data class Reacted(val chat: String, val id: String, val emoji: String, val sender: String?)
data class Deleted(val chat: String, val id: String, val everyone: Boolean)
data class Edited(val chat: String, val id: String, val message: String)
data class PinCall(val chat: String, val pinned: Boolean)

/** One recorded `search` request. */
data class SearchCall(val q: String, val chat: String?, val fromMe: Boolean?, val media: String?, val limit: Int, val offset: Int?)

/**
 * Scriptable in-memory [WhatsAppRepository]: set the `*Result` properties (or [messagesResponder]) before
 * the action under test, read the recorded calls after, and push live messages through [emitLive].
 */
class FakeWhatsAppRepository : WhatsAppRepository {
    var statusResult: ApiResult<WaStatus> = ApiResult.Ok(WaStatus(enabled = true, unreadTotal = 0))
    var chatsResult: ApiResult<List<WaChat>> = ApiResult.Ok(emptyList())
    var timelineResult: ApiResult<List<WaTimelineDay>> = ApiResult.Ok(emptyList())
    var contactsResult: ApiResult<List<WaContact>> = ApiResult.Ok(emptyList())
    var avatarsResult: ApiResult<WaAvatarsRefreshResponse> = ApiResult.Ok(WaAvatarsRefreshResponse())

    /** Result for every write call (send, react, delete, edit, pin, mark read, forward, latest). */
    var actionResult: ApiResult<Unit> = ApiResult.Ok(Unit)

    /** Answers each `messages` request; defaults to an empty page. */
    var messagesResponder: (MessagesCall) -> ApiResult<List<WaMessage>> = { ApiResult.Ok(emptyList()) }

    val statusCalls = mutableListOf<Unit>()
    val chatsCalls = mutableListOf<Unit>()
    val messageCalls = mutableListOf<MessagesCall>()
    val timelineCalls = mutableListOf<String>()
    val avatarCalls = mutableListOf<List<String>>()
    val sentTexts = mutableListOf<SentText>()
    val sentMedia = mutableListOf<SentMedia>()
    val sentVoices = mutableListOf<SentVoice>()
    val reactions = mutableListOf<Reacted>()
    val deletes = mutableListOf<Deleted>()
    val edits = mutableListOf<Edited>()
    val pins = mutableListOf<PinCall>()
    val localPins = mutableListOf<PinCall>()
    val chatNames = mutableListOf<Pair<String, String>>()
    val markedRead = mutableListOf<String>()
    val latestFetched = mutableListOf<String>()

    /** Answers each `search` request; defaults to an empty page. */
    var searchResponder: (SearchCall) -> ApiResult<WaSearchResponse> = { ApiResult.Ok(WaSearchResponse()) }
    var searchDaysResult: ApiResult<List<WaTimelineDay>> = ApiResult.Ok(emptyList())
    var chatSearchStatusResult: ApiResult<WaChatSearchStatus> = ApiResult.Ok(WaChatSearchStatus())
    var chatSearchResult: ApiResult<WaChatSearchResponse> = ApiResult.Ok(WaChatSearchResponse())
    var pickResult: ApiResult<WaPickCounts> = ApiResult.Ok(WaPickCounts())
    val searchCalls = mutableListOf<SearchCall>()
    val searchDaysCalls = mutableListOf<Pair<String, String>>()
    val chatSearchQueries = mutableListOf<String>()
    val picks = mutableListOf<WaPickReq>()
    var statusReads = 0
    var indexRequests = 0
    var cardsRuns = 0
    var cardsStops = 0

    /** When set, `chats()`/`status()` park on it until completed — for single-flight tests. */
    var chatsGate: CompletableDeferred<Unit>? = null
    var statusGate: CompletableDeferred<Unit>? = null

    /** When set, `search()` parks on it (after recording the call) until completed - for stale-response tests. */
    var searchGate: CompletableDeferred<Unit>? = null

    /** When set, `chatSearchPick()` parks on it (after recording the body) until completed. */
    var pickGate: CompletableDeferred<Unit>? = null

    private val live = MutableSharedFlow<WaMessage>(extraBufferCapacity = 64)

    fun emitLive(msg: WaMessage) { check(live.tryEmit(msg)) }

    override suspend fun status(): ApiResult<WaStatus> {
        statusCalls += Unit
        statusGate?.await()
        return statusResult
    }

    override suspend fun chats(): ApiResult<List<WaChat>> {
        chatsCalls += Unit
        chatsGate?.await()
        return chatsResult
    }

    override suspend fun messages(
        chat: String,
        limit: Int,
        before: String?,
        after: String?,
        asc: Boolean?,
    ): ApiResult<List<WaMessage>> {
        val call = MessagesCall(chat, limit, before, after, asc)
        messageCalls += call
        return messagesResponder(call)
    }

    override suspend fun timeline(chat: String): ApiResult<List<WaTimelineDay>> {
        timelineCalls += chat
        return timelineResult
    }

    override suspend fun searchContacts(query: String): ApiResult<List<WaContact>> = contactsResult

    override suspend fun sendText(to: String, message: String, replyTo: String?): ApiResult<Unit> {
        sentTexts += SentText(to, message, replyTo)
        return actionResult
    }

    override suspend fun sendMedia(to: String, upload: UploadFile, caption: String): ApiResult<Unit> {
        sentMedia += SentMedia(to, upload.name, caption)
        return actionResult
    }

    override suspend fun sendVoice(to: String, replyTo: String?, audio: File): ApiResult<Unit> {
        sentVoices += SentVoice(to, replyTo, audio)
        return actionResult
    }

    override suspend fun react(chat: String, id: String, emoji: String, sender: String?): ApiResult<Unit> {
        reactions += Reacted(chat, id, emoji, sender)
        return actionResult
    }

    override suspend fun forward(chat: String, id: String, to: String): ApiResult<Unit> = actionResult

    override suspend fun delete(chat: String, id: String, everyone: Boolean): ApiResult<Unit> {
        deletes += Deleted(chat, id, everyone)
        return actionResult
    }

    override suspend fun edit(chat: String, id: String, message: String): ApiResult<Unit> {
        edits += Edited(chat, id, message)
        return actionResult
    }

    override suspend fun setPinned(chat: String, pinned: Boolean): ApiResult<Unit> {
        pins += PinCall(chat, pinned)
        return actionResult
    }

    override suspend fun setLocalPin(chat: String, pinned: Boolean): ApiResult<Unit> {
        localPins += PinCall(chat, pinned)
        return actionResult
    }

    /** Stores like the server: trimmed, blank removes. */
    override suspend fun setChatName(chat: String, name: String): ApiResult<String?> {
        chatNames += chat to name
        return when (val r = actionResult) {
            is ApiResult.Ok -> ApiResult.Ok(name.trim().ifBlank { null })
            is ApiResult.Err -> r
        }
    }

    override suspend fun markRead(chat: String): ApiResult<Unit> {
        markedRead += chat
        return actionResult
    }

    override suspend fun fetchLatest(chat: String): ApiResult<Unit> {
        latestFetched += chat
        return actionResult
    }

    override suspend fun refreshAvatars(jids: List<String>, limit: Int): ApiResult<WaAvatarsRefreshResponse> {
        avatarCalls += jids
        return avatarsResult
    }

    override suspend fun search(
        q: String,
        chat: String?,
        fromMe: Boolean?,
        media: String?,
        limit: Int,
        offset: Int?,
    ): ApiResult<WaSearchResponse> {
        val call = SearchCall(q, chat, fromMe, media, limit, offset)
        searchCalls += call
        searchGate?.await()
        return searchResponder(call)
    }

    override suspend fun searchDays(q: String, chat: String): ApiResult<List<WaTimelineDay>> {
        searchDaysCalls += q to chat
        return searchDaysResult
    }

    override suspend fun chatSearchStatus(): ApiResult<WaChatSearchStatus> {
        statusReads++
        return chatSearchStatusResult
    }

    override suspend fun chatSearchIndex(): ApiResult<Unit> {
        indexRequests++
        return actionResult
    }

    override suspend fun chatSearchCardsRun(): ApiResult<Unit> {
        cardsRuns++
        return actionResult
    }

    override suspend fun chatSearchCardsStop(): ApiResult<Unit> {
        cardsStops++
        return actionResult
    }

    override suspend fun chatSearch(q: String, limit: Int): ApiResult<WaChatSearchResponse> {
        chatSearchQueries += q
        return chatSearchResult
    }

    override suspend fun chatSearchPick(body: WaPickReq): ApiResult<WaPickCounts> {
        picks += body
        pickGate?.await()
        return pickResult
    }

    override fun mediaUrl(chat: String, id: String, thumb: Boolean): String = "https://fake.test/media/$chat/$id"
    override fun avatarUrl(jid: String): String = "https://fake.test/avatar/$jid"

    override fun liveMessages(): Flow<WaMessage> = live
}

/** [UploadSource] over canned files keyed by reference. */
class FakeUploadSource(private val files: Map<String, UploadFile> = emptyMap()) : UploadSource {
    override fun displayName(ref: String): String = files[ref]?.name ?: "upload.bin"
    override suspend fun describe(ref: String): UploadFile = files.getValue(ref)
}

/** Counts prefetches and answers saves without touching Coil or the gallery. */
class FakeMediaTools : WhatsAppMediaTools {
    val prefetched = mutableListOf<List<WaMessage>>()
    var saveResult: Result<String> = Result.success("Pictures/test.jpg")

    override fun prefetch(page: List<WaMessage>) { prefetched += page }
    override suspend fun saveImage(msg: WaMessage): String = saveResult.getOrThrow()
}
