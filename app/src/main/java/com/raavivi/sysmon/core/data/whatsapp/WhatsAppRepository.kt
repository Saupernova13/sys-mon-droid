package com.raavivi.sysmon.core.data.whatsapp

import com.raavivi.sysmon.core.model.WaAvatarsRefreshResponse
import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.model.WaContact
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaStatus
import com.raavivi.sysmon.core.model.WaTimelineDay
import com.raavivi.sysmon.core.net.ApiResult
import kotlinx.coroutines.flow.Flow
import java.io.File

/** Everything the WhatsApp screen needs from the sys-mon server, as app models rather than wire JSON. */
interface WhatsAppRepository {
    suspend fun status(): ApiResult<WaStatus>

    /** Chats in server order; the caller sorts them. */
    suspend fun chats(): ApiResult<List<WaChat>>

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
    suspend fun markRead(chat: String): ApiResult<Unit>

    /** Have the server download missing media for the chat's newest messages (can take tens of seconds). */
    suspend fun fetchLatest(chat: String): ApiResult<Unit>
    suspend fun refreshAvatars(jids: List<String>, limit: Int): ApiResult<WaAvatarsRefreshResponse>

    fun mediaUrl(chat: String, id: String, thumb: Boolean = false): String
    fun avatarUrl(jid: String): String

    /**
     * Live messages from the server. Cold: the socket opens on collection and closes on cancellation.
     * Completes when the socket closes or fails; it never throws, and there is no automatic reconnect.
     */
    fun liveMessages(): Flow<WaMessage>
}
