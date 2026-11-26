package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

// Wire shapes of the "your own cards" routes (`/api/whatsapp/chat-search/cards/manual*`, the card delete) and
// of `your_cards` in a conversation search. Message text, descriptions and card text are private: nothing
// here is ever logged, and a job never carries the description or the message ids.

/** `POST .../cards/manual`: 1 to 400 [msgIds] of [chat] and an optional [instruction] (null is left out of the JSON). */
@Serializable
data class WaManualCardReq(val chat: String, val msgIds: List<String>, val instruction: String? = null)

/**
 * A card job. [state] is `queued`, `writing`, `done`, `failed` or `cancelled`; [cardId] is set once done;
 * [error] is a fixed kind once failed; [waiting] is `game`, `gpu`, `index` or `model_starting` while an open
 * job is held, else null.
 */
@Serializable
data class WaManualJob(
    val id: Long = 0,
    val state: String = "queued",
    val chat: String = "",
    val nMsgs: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val cardId: Long? = null,
    val error: String? = null,
    val waiting: String? = null,
)

/** The 202 answer of `POST .../cards/manual`. */
@Serializable
data class WaManualJobResponse(val job: WaManualJob = WaManualJob())

/** `ts` and `rowid` of the first or last picked message. */
@Serializable
data class WaPos(val ts: Long = 0, val rowid: Long = 0)

/** One of the owner's own cards that matched a conversation search (`your_cards`). */
@Serializable
data class WaYourCard(
    val cardId: Long = 0,
    val chat: String = "",
    val chatName: String = "",
    val title: String = "",
    val summary: String = "",
    val instruction: String? = null,
    val start: WaPos = WaPos(),
    val end: WaPos = WaPos(),
    val nMsgs: Int = 0,
    val msgIds: List<String> = emptyList(),
    val score: Double = 0.0,
    /** Unix seconds of the owner's last edit; null for a card never edited. */
    val editedAt: Long? = null,
    /** The card's themes (names, writer's order). */
    val facets: List<String> = emptyList(),
)

/** Why a request about a manual card was refused, from the status and the server's code (see [manualErrorOf]). */
sealed interface ManualCardError {
    /** 400: the server's own [reason] (a fixed sentence; never the description or the messages). */
    data class Refused(val reason: String) : ManualCardError

    /** 409 `too_many`: five jobs are already waiting. */
    data object TooMany : ManualCardError

    /** 409 `no_model`: no card model is set up on the server. */
    data object NoModel : ManualCardError

    /** 409 `finished`: the job ended before it could be cancelled. */
    data object Finished : ManualCardError

    /** 409 `not_manual`: only a card the owner asked for can be deleted. */
    data object NotManual : ManualCardError

    /** 404: chat search is off (or the job or card is unknown). */
    data object Off : ManualCardError

    /** 503: a store cannot be used right now. */
    data object Unavailable : ManualCardError

    /** Anything else (network, a server fault). */
    data object Other : ManualCardError
}
