package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire shapes of the WhatsApp search routes (`/api/whatsapp/search*`, `/api/whatsapp/chat-search*`).
// Query text, snippets and card text are private: nothing here is ever logged.

/** What the server actually searched for; the client highlights it the same way. */
@Serializable
data class WaSearchTerm(
    val text: String = "",
    val prefix: Boolean = false,
    val phrase: Boolean = false,
    val negated: Boolean = false,
    val indexable: Boolean = true,
)

/** One message hit. [snippet] wraps the matched words in `\u0002`..`\u0003`; [voice] is a voice-note transcript hit. */
@Serializable
data class WaSearchRow(
    val chatJid: String = "",
    val chatName: String = "",
    val msgId: String = "",
    val ts: Long = 0,
    val snippet: String = "",
    val voice: Boolean = false,
)

/** `GET /api/whatsapp/search`; [nextOffset] is absent on the last page, [totalCapped] means "1000+". */
@Serializable
data class WaSearchResponse(
    val results: List<WaSearchRow> = emptyList(),
    val total: Int = 0,
    val totalCapped: Boolean = false,
    val nextOffset: Int? = null,
    val terms: List<WaSearchTerm> = emptyList(),
    val engine: String = "",
)

/** The cards the server wrote for a topic; [ofParent] when it is the parent topic's card on a sub-topic result. */
@Serializable
data class WaConvCard(val id: Long = 0, val title: String = "", val summary: String = "", val ofParent: Boolean = false)

/** A sub-topic result's parent topic. */
@Serializable
data class WaConvCrumb(
    val nodeId: Long = 0,
    val level: Int = 1,
    val startTs: Long = 0,
    val endTs: Long = 0,
    val nMsgs: Int = 0,
)

/** One preview line of a conversation result. */
@Serializable
data class WaConvLine(
    val chatJid: String = "",
    val msgId: String = "",
    val ts: Long = 0,
    val fromMe: Boolean = false,
    val senderName: String = "",
    val text: String = "",
)

/** A conversation (topic or sub-topic node) a conversation search found. */
@Serializable
data class WaConvResult(
    val nodeId: Long = 0,
    val level: Int = 1,
    val chat: String = "",
    val family: List<String> = emptyList(),
    val chatName: String = "",
    val startTs: Long = 0,
    val startRowid: Long = 0,
    val endTs: Long = 0,
    val endRowid: Long = 0,
    val nMsgs: Int = 0,
    val breadcrumb: WaConvCrumb? = null,
    val score: Double = 0.0,
    val preview: List<WaConvLine> = emptyList(),
    val card: WaConvCard? = null,
)

/** `GET /api/whatsapp/chat-search`; [degraded] is `"keyword"` when no embedder answered (served with a 503). */
@Serializable
data class WaChatSearchResponse(
    val results: List<WaConvResult> = emptyList(),
    val tookMs: Double = 0.0,
    val reranked: Boolean = false,
    val degraded: String? = null,
)

/** The transcriber's counts; only `pending` is shown. */
@Serializable
data class WaVoiceNotesStatus(val pending: Int? = null)

/** The topic-card run's state (counts and timings only, never a card). [lastError] is a kind string (or `{kind}`). */
@Serializable
data class WaCardsStatus(
    val configured: Boolean = false,
    val requested: Boolean = false,
    val auto: Boolean = false,
    val mode: String = "",
    val eligiblePending: Int? = null,
    val done: Int? = null,
    val breakUntil: Double? = null,
    val nextWindowStart: Double? = null,
    val lastError: JsonElement? = null,
)

/** `GET /api/whatsapp/chat-search/status`; `{"enabled": false}` when the feature is off. */
@Serializable
data class WaChatSearchStatus(
    val enabled: Boolean = false,
    val auto: Boolean? = null,
    val runRequested: Boolean = false,
    val messagesIndexed: Long? = null,
    val messagesTotal: Long? = null,
    val nodeVectors: Long = 0,
    val voiceNotes: WaVoiceNotesStatus? = null,
    val cards: WaCardsStatus? = null,
)

/** Answer of the index / cards run and stop routes; only the flags the caller may read. */
@Serializable
data class WaRunResponse(val runRequested: Boolean = false, val cardsRequested: Boolean = false)

/** The result a "Found it" verdict names, as the search returned it. */
@Serializable
data class WaPickHit(
    val family: List<String>,
    val startTs: Long,
    val startRowid: Long,
    val endTs: Long,
    val endRowid: Long,
)

/** `POST /api/whatsapp/chat-search/pick`: [hit] and [rank] go with `found` only. */
@Serializable
data class WaPickReq(
    val q: String,
    val verdict: String,
    val hit: WaPickHit? = null,
    val rank: Int? = null,
    val shown: Int,
)

/** How many queries are marked each way. */
@Serializable
data class WaPickCounts(val found: Int = 0, val notHere: Int = 0)
