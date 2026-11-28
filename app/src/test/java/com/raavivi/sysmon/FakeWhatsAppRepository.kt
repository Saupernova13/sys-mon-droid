package com.raavivi.sysmon

import com.raavivi.sysmon.core.data.whatsapp.UploadFile
import com.raavivi.sysmon.core.data.whatsapp.UploadSource
import com.raavivi.sysmon.core.data.whatsapp.WhatsAppRepository
import com.raavivi.sysmon.core.model.WaAvatarsRefreshResponse
import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.model.WaCardDetail
import com.raavivi.sysmon.core.model.WaCardChat
import com.raavivi.sysmon.core.model.WaCardSettings
import com.raavivi.sysmon.core.model.WaCardSettingsPatch
import com.raavivi.sysmon.core.model.WaChatSearchResponse
import com.raavivi.sysmon.core.model.WaChatSearchStatus
import com.raavivi.sysmon.core.model.WaContact
import com.raavivi.sysmon.core.model.WaManualJob
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaPickCounts
import com.raavivi.sysmon.core.model.WaPickReq
import com.raavivi.sysmon.core.model.WaTagSuggestResponse
import com.raavivi.sysmon.core.model.WaTagsReq
import com.raavivi.sysmon.core.model.WaTagsResponse
import com.raavivi.sysmon.core.model.WaSearchResponse
import com.raavivi.sysmon.core.model.WaSimilarResponse
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

    /** The themes each chat search carried (empty when none were picked), in call order. */
    val chatSearchThemes = mutableListOf<List<String>>()

    /** Whether each chat search was deep, in call order. */
    val chatSearchDeep = mutableListOf<Boolean>()

    /** When set, `chatSearch()` parks on it (after recording the call and taking its answer) until completed. */
    var chatSearchGate: CompletableDeferred<Unit>? = null
    val picks = mutableListOf<WaPickReq>()
    var cardResult: ApiResult<WaCardDetail> = ApiResult.Ok(WaCardDetail())
    val cardRequests = mutableListOf<Long>()

    /** Card ids asked for by `chatSearchCardById()`. */
    val cardIdRequests = mutableListOf<Long>()

    /** What `chatSearchTags()` answers (or a queue of answers: the first is used and removed while any remain). */
    var tagsResult: ApiResult<WaTagsResponse> = ApiResult.Ok(WaTagsResponse())
    val tagsResults = ArrayDeque<ApiResult<WaTagsResponse>>()

    /** The bodies `chatSearchTags()` was called with. */
    val tagsQueries = mutableListOf<WaTagsReq>()

    /** When set, `chatSearchTags()` parks on it (after recording the body) until completed. */
    var tagsGate: CompletableDeferred<Unit>? = null

    var suggestResult: ApiResult<WaTagSuggestResponse> = ApiResult.Ok(WaTagSuggestResponse())

    /** The (cat, q, limit) of each type-ahead call. */
    val suggestCalls = mutableListOf<Triple<String, String, Int>>()

    /** When set, `chatSearchTagSuggest()` parks on it (after recording the call) until completed. */
    var suggestGate: CompletableDeferred<Unit>? = null
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

    /** When set, `chatSearchCard()` parks on it (after recording the node) until completed. */
    var cardGate: CompletableDeferred<Unit>? = null

    private val live = MutableSharedFlow<WaMessage>(extraBufferCapacity = 64)

    fun emitLive(msg: WaMessage) { check(live.tryEmit(msg)) }

    override suspend fun status(): ApiResult<WaStatus> {
        statusCalls += Unit
        statusGate?.await()
        return statusResult
    }

    override suspend fun chats(limit: Int): ApiResult<List<WaChat>> {
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

    /** What `cardSettings()` answers (the probe, when asked for, is looked up in [probeChats]). */
    var cardSettingsResult: ApiResult<WaCardSettings> = ApiResult.Ok(WaCardSettings())
    var probeChats: Map<String, WaCardChat> = emptyMap()
    var probeFails = false

    /** The probe jid of each `cardSettings()` call (null for a plain read). */
    val cardSettingsReads = mutableListOf<String?>()

    /** What `saveCardSettings()` answers; null answers the saved settings with the patch applied. */
    var saveCardSettingsResult: ApiResult<WaCardSettings>? = null
    val cardSettingsSaves = mutableListOf<WaCardSettingsPatch>()

    /** When set, `cardSettings()` and `saveCardSettings()` park on it (after recording the call) until completed. */
    var cardSettingsGate: CompletableDeferred<Unit>? = null

    override suspend fun cardSettings(probe: String?): ApiResult<WaCardSettings> {
        cardSettingsReads += probe
        cardSettingsGate?.await()
        if (probe == null) return cardSettingsResult
        if (probeFails) return ApiResult.Err("HTTP 400", 400)
        val base = (cardSettingsResult as? ApiResult.Ok)?.value ?: WaCardSettings()
        return ApiResult.Ok(base.copy(probe = probeChats[probe] ?: WaCardChat(jid = probe)))
    }

    override suspend fun saveCardSettings(patch: WaCardSettingsPatch): ApiResult<WaCardSettings> {
        cardSettingsSaves += patch
        cardSettingsGate?.await()
        saveCardSettingsResult?.let { return it }
        val base = (cardSettingsResult as? ApiResult.Ok)?.value ?: WaCardSettings()
        return ApiResult.Ok(
            base.copy(
                window = patch.window ?: base.window,
                minMsgs = patch.minMsgs ?: base.minMsgs,
                chats = patch.chats?.map { jid -> WaCardChat(jid = jid) } ?: base.chats,
                facets = patch.facets ?: base.facets,
            ),
        )
    }

    override suspend fun chatSearchCardsRun(): ApiResult<Unit> {
        cardsRuns++
        return actionResult
    }

    override suspend fun chatSearchCardsStop(): ApiResult<Unit> {
        cardsStops++
        return actionResult
    }

    override suspend fun chatSearch(q: String, limit: Int, themes: List<String>, deep: Boolean): ApiResult<WaChatSearchResponse> {
        chatSearchQueries += q
        chatSearchThemes.add(themes)
        chatSearchDeep += deep
        val result = chatSearchResult
        chatSearchGate?.await()
        return result
    }

    override suspend fun chatSearchPick(body: WaPickReq): ApiResult<WaPickCounts> {
        picks += body
        pickGate?.await()
        return pickResult
    }

    override suspend fun chatSearchCard(node: Long): ApiResult<WaCardDetail> {
        cardRequests += node
        cardGate?.await()
        return cardResult
    }

    /** What `chatSearchSimilar()` answers, the nodes it was asked about, and a gate it parks on (after recording the node). */
    var similarResult: ApiResult<WaSimilarResponse> = ApiResult.Ok(WaSimilarResponse())
    val similarRequests = mutableListOf<Long>()
    var similarGate: CompletableDeferred<Unit>? = null

    override suspend fun chatSearchSimilar(node: Long): ApiResult<WaSimilarResponse> {
        similarRequests += node
        similarGate?.await()
        return similarResult
    }

    override suspend fun chatSearchCardById(card: Long): ApiResult<WaCardDetail> {
        cardIdRequests += card
        cardGate?.await()
        return cardResult
    }

    // -- your own cards ---------------------------------------------------------------------------

    var createManualResult: ApiResult<WaManualJob> = ApiResult.Ok(WaManualJob(id = 1, state = "queued", chat = "", nMsgs = 0))

    /** Answers each `manualJob()` read; defaults to the created job, still queued. */
    var manualJobResponder: (Long) -> ApiResult<WaManualJob> = { ApiResult.Ok(WaManualJob(id = it, state = "queued")) }
    var cancelManualResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var deleteCardResult: ApiResult<Unit> = ApiResult.Ok(Unit)

    /** (chat, ids, instruction) of each `createManualCard()` call. */
    val manualRequests = mutableListOf<Triple<String, List<String>, String?>>()
    val manualJobReads = mutableListOf<Long>()
    val manualCancels = mutableListOf<Long>()
    val cardDeletes = mutableListOf<Long>()

    override suspend fun createManualCard(chat: String, msgIds: List<String>, instruction: String?): ApiResult<WaManualJob> {
        manualRequests += Triple(chat, msgIds, instruction)
        return createManualResult
    }

    override suspend fun manualJob(id: Long): ApiResult<WaManualJob> {
        manualJobReads += id
        return manualJobResponder(id)
    }

    override suspend fun cancelManualJob(id: Long): ApiResult<Unit> {
        manualCancels += id
        return cancelManualResult
    }

    override suspend fun deleteCard(id: Long): ApiResult<Unit> {
        cardDeletes += id
        return deleteCardResult
    }

    override suspend fun chatSearchTags(body: WaTagsReq): ApiResult<WaTagsResponse> {
        tagsQueries += body
        // The answer is chosen at call time, so a parked call keeps the one it was given.
        val answer = tagsResults.removeFirstOrNull() ?: tagsResult
        tagsGate?.await()
        return answer
    }

    override suspend fun chatSearchTagSuggest(cat: String, q: String, limit: Int): ApiResult<WaTagSuggestResponse> {
        suggestCalls += Triple(cat, q, limit)
        val answer = suggestResult
        suggestGate?.await()
        return answer
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
