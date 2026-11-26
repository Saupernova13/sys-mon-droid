package com.raavivi.sysmon.core.data.whatsapp

import com.raavivi.sysmon.core.model.WaAvatarsRefreshResponse
import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.model.WaCardDetail
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
import com.raavivi.sysmon.core.model.WaStatus
import com.raavivi.sysmon.core.model.WaTimelineDay
import com.raavivi.sysmon.core.net.ApiResult
import kotlinx.coroutines.flow.Flow
import java.io.File

/** Everything the WhatsApp screen needs from the sys-mon server, as app models rather than wire JSON. */
interface WhatsAppRepository {
    suspend fun status(): ApiResult<WaStatus>

    /** Chats in server order; the caller sorts them. */
    suspend fun chats(limit: Int = 100): ApiResult<List<WaChat>>

    /** One page of [chat]; [before] / [after] are the server's timestamp cursors, [asc] flips the order. */
    suspend fun messages(
        chat: String,
        limit: Int,
        before: String? = null,
        after: String? = null,
        asc: Boolean? = null,
    ): ApiResult<List<WaMessage>>

    suspend fun timeline(chat: String): ApiResult<List<WaTimelineDay>>
    suspend fun searchContacts(query: String): ApiResult<List<WaContact>>

    suspend fun sendText(to: String, message: String, replyTo: String?): ApiResult<Unit>
    suspend fun sendMedia(to: String, upload: UploadFile, caption: String): ApiResult<Unit>
    suspend fun sendVoice(to: String, replyTo: String?, audio: File): ApiResult<Unit>

    /** An empty [emoji] removes the reaction; [sender] is the target's sender, required in groups. */
    suspend fun react(chat: String, id: String, emoji: String, sender: String?): ApiResult<Unit>
    suspend fun forward(chat: String, id: String, to: String): ApiResult<Unit>
    suspend fun delete(chat: String, id: String, everyone: Boolean): ApiResult<Unit>
    suspend fun edit(chat: String, id: String, message: String): ApiResult<Unit>

    /** WhatsApp's own pin. */
    suspend fun setPinned(chat: String, pinned: Boolean): ApiResult<Unit>

    /** sys-mon's own pin list. */
    suspend fun setLocalPin(chat: String, pinned: Boolean): ApiResult<Unit>

    /** sys-mon's own chat name; a blank [name] removes it. Ok carries the stored name, null once removed. */
    suspend fun setChatName(chat: String, name: String): ApiResult<String?>
    suspend fun markRead(chat: String): ApiResult<Unit>

    /** Have the server download missing media for the chat's newest messages (can take tens of seconds). */
    suspend fun fetchLatest(chat: String): ApiResult<Unit>
    suspend fun refreshAvatars(jids: List<String>, limit: Int): ApiResult<WaAvatarsRefreshResponse>

    // -- search (query text is private: callers never log it) ---------------------------------

    /** One page of keyword hits over every chat, or only [chat]; [fromMe] / [media] filter, [offset] pages. */
    suspend fun search(
        q: String,
        chat: String? = null,
        fromMe: Boolean? = null,
        media: String? = null,
        limit: Int,
        offset: Int? = null,
    ): ApiResult<WaSearchResponse>

    /** The days of [chat] that have hits for [q], for marking the scrubber. */
    suspend fun searchDays(q: String, chat: String): ApiResult<List<WaTimelineDay>>

    suspend fun chatSearchStatus(): ApiResult<WaChatSearchStatus>
    suspend fun chatSearchIndex(): ApiResult<Unit>
    suspend fun chatSearchCardsRun(): ApiResult<Unit>
    suspend fun chatSearchCardsStop(): ApiResult<Unit>

    /** The card writer's settings; [probe] (a chat id) adds what carding that chat would take. 404: chat search is off. */
    suspend fun cardSettings(probe: String? = null): ApiResult<WaCardSettings>

    /** Saves the fields set in [patch] and answers the settings as saved. A 400's reason is the Err message. */
    suspend fun saveCardSettings(patch: WaCardSettingsPatch): ApiResult<WaCardSettings>

    /** Conversation search; a word-only answer (the server's 503) is Ok with `degraded` set. */
    suspend fun chatSearch(q: String, limit: Int): ApiResult<WaChatSearchResponse>
    suspend fun chatSearchPick(body: WaPickReq): ApiResult<WaPickCounts>

    /** The full topic card of the result node [node]; an Err with code 404 means it has none. */
    suspend fun chatSearchCard(node: Long): ApiResult<WaCardDetail>

    /** The full topic card with card id [card] (a tag result whose topic node is gone); an Err with code 404 means none. */
    suspend fun chatSearchCardById(card: Long): ApiResult<WaCardDetail>

    // -- your own cards (#392). Failures carry the status and the envelope's code: see [manualErrorOf]. ----

    /** Ask for a card over [msgIds] of [chat]; [instruction] (at most 500 characters, null for none) steers it. */
    suspend fun createManualCard(chat: String, msgIds: List<String>, instruction: String?): ApiResult<WaManualJob>

    /** One job; an Err with code 404 means it is gone (finished jobs are kept 50 deep). */
    suspend fun manualJob(id: Long): ApiResult<WaManualJob>

    /** Cancel a job. 409 `finished`: it already ended. */
    suspend fun cancelManualJob(id: Long): ApiResult<Unit>

    /** Delete a card the owner asked for. 409 `not_manual`: a topic card cannot be deleted. */
    suspend fun deleteCard(id: Long): ApiResult<Unit>

    /** Tag search over the topic cards; an Err with code 404 means chat search is off. */
    suspend fun chatSearchTags(body: WaTagsReq): ApiResult<WaTagsResponse>

    /** Tag type-ahead for [q] (blank = the most used), within category [cat] (blank = all). */
    suspend fun chatSearchTagSuggest(cat: String, q: String, limit: Int): ApiResult<WaTagSuggestResponse>

    fun mediaUrl(chat: String, id: String, thumb: Boolean = false): String
    fun avatarUrl(jid: String): String

    /**
     * Live messages from the server. Cold: the socket opens on collection and closes on cancellation.
     * Completes when the socket closes or fails; it never throws, and there is no automatic reconnect.
     */
    fun liveMessages(): Flow<WaMessage>
}
