package com.raavivi.sysmon.ui.whatsapp

import com.raavivi.sysmon.core.model.ManualCardError
import com.raavivi.sysmon.core.model.WaManualJob
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaYourCard
import java.time.ZoneId

/**
 * "Your cards" (#392) as pure decisions with no UI: the set of picked messages and the anchor a range grows
 * from, the caps, and every sentence the bar, the Make card sheet and the card view say. Port of the web
 * whatsapp-select-state.js / whatsapp-manual-state.js, texts included. Message text and descriptions are
 * private: nothing here logs.
 */

/** The most messages one card may cover (the server's limit). */
const val MAX_PICKS = 400

/** The longest description the server accepts. */
const val MAX_INSTRUCTION = 500

/** How often the open sheet asks for its job; a dismissed sheet's job is watched more gently. */
const val MANUAL_POLL_MS = 2000L
const val MANUAL_WATCH_MS = 5000L

/** A message that can be picked: it has an id, is not deleted and is not a reaction. */
fun waCanSelect(m: WaMessage): Boolean = m.id.isNotBlank() && !m.revoked && !m.deletedForMe && !m.isReaction

/** The menu offers "Select messages" while chat search is [live] and only for a message that [waCanSelect]. */
fun selectOffered(live: Boolean, m: WaMessage): Boolean = live && waCanSelect(m)

/** A pickable row in display order: its message id and time (unix seconds). */
data class PickRow(val id: String, val ts: Double)

/** The pickable rows of the loaded messages, oldest first, for range picking. */
fun pickOrder(messages: List<WaMessage>): List<PickRow> =
    messages.filter { isVisibleMessage(it) && waCanSelect(it) }.map { PickRow(it.id, it.ts) }

/** Whether Make card is allowed: at least one message and no more than the cap. */
fun canMake(n: Int): Boolean = n in 1..MAX_PICKS

/** The bar's live count; over the cap it says why Make card is off. */
fun barText(n: Int): String = if (n > MAX_PICKS) "$n selected (at most $MAX_PICKS)" else "$n selected"

/**
 * The picked messages (id to time, in the order picked) and the anchor a long-press range grows from (the
 * last one toggled). A value: every change returns a new one.
 */
data class Selection(val picks: Map<String, Double> = emptyMap(), val anchor: String? = null) {
    val count: Int get() = picks.size

    fun has(id: String): Boolean = id in picks

    /** A tap: the message joins the selection, or leaves it, and becomes the anchor. */
    fun toggled(id: String, ts: Double): Selection {
        val next = LinkedHashMap(picks)
        if (next.remove(id) == null) next[id] = ts
        return Selection(next, id)
    }

    /**
     * A long-press: every message between the anchor and [id], either way round and both ends included, joins
     * the selection. [order] is the pickable rows in display order. With no anchor on screen it picks just [id].
     * The anchor stays, so the next range grows from the same place.
     */
    fun withRange(order: List<PickRow>, id: String, ts: Double): Selection {
        val from = order.indexOfFirst { it.id == anchor }
        val to = order.indexOfFirst { it.id == id }
        val next = LinkedHashMap(picks)
        if (from < 0 || to < 0) {
            next[id] = ts
            return Selection(next, id)
        }
        for (i in minOf(from, to)..maxOf(from, to)) next[order[i].id] = order[i].ts
        return Selection(next, anchor)
    }

    /** The picked ids, oldest message first. */
    fun ids(): List<String> = picks.entries.sortedBy { it.value }.map { it.key }

    /** The first and last picked time (unix seconds), or null with none picked. */
    fun span(): Pair<Long, Long>? =
        picks.values.takeIf { it.isNotEmpty() }?.let { it.min().toLong() to it.max().toLong() }
}

/** "3 messages · 14 March 2025 · 19:02–20:10", or two days when it spans them. */
fun manualSummary(n: Int, first: Long?, last: Long?, nowSeconds: Double, zone: ZoneId = ZoneId.systemDefault()): String {
    val what = messagesLabel(n)
    if (first == null || last == null) return what
    val sameDay = waDayKey(first.toDouble(), zone) == waDayKey(last.toDouble(), zone)
    val when_ = if (sameDay) convRange(first, last, nowSeconds, zone)
    else "${waDayLabel(first.toDouble(), nowSeconds, zone)} – ${waDayLabel(last.toDouble(), nowSeconds, zone)}"
    return "$what · $when_"
}

/** The counter under the description: characters used, or how far over the limit. */
fun instructionCounter(len: Int): String {
    val left = MAX_INSTRUCTION - len
    return if (left >= 0) "$len / $MAX_INSTRUCTION" else "${-left} over the limit of $MAX_INSTRUCTION"
}

/** Whether the description may be sent: optional, but not over the limit. */
fun instructionOk(text: String): Boolean = text.length <= MAX_INSTRUCTION

/** A job that will not change any more. */
fun isFinished(job: WaManualJob?): Boolean = job != null && job.state in FINISHED_STATES

private val FINISHED_STATES = setOf("done", "failed", "cancelled")

/** What a job's view shows: two replies with the same key look the same. */
fun jobKey(job: WaManualJob?): String = job?.let { "${it.state}|${it.waiting}|${it.error}|${it.cardId}" }.orEmpty()

/** One line for where a job is: its state, or what it is waiting for. */
fun jobProgressText(job: WaManualJob): String = when {
    job.state == "done" -> "Done"
    job.state == "failed" -> "The card could not be written"
    job.state == "cancelled" -> "Cancelled"
    job.waiting == "game" -> "Waiting for the game to close"
    job.waiting == "gpu" -> "Waiting for the GPU"
    job.waiting == "index" -> "Waiting for the index to finish"
    job.waiting == "model_starting" -> "Starting the model"
    job.state == "writing" -> "Writing your card"
    else -> "Queued"
}

/** Plain words for a failed job's fixed error kind. */
fun jobFailureText(kind: String?): String = when (kind) {
    "model_call_failed" -> "The card model did not answer. Try again in a moment."
    "bad_reply" -> "The card model gave a reply that could not be used. Try again, or describe the card differently."
    "server_unavailable" -> "The card model could not be started. Check Settings > WhatsApp > Topic cards."
    "no_messages" -> "None of the selected messages could be read. They may have been deleted."
    else -> "The card could not be written."
}

/** What a refused request tells the owner: the server's reason on a 400, a clear message on the rest. */
fun manualErrorText(e: ManualCardError): String = when (e) {
    is ManualCardError.Refused -> e.reason
    ManualCardError.TooMany -> "Five cards are already waiting. Wait for one to finish or cancel one, then try again."
    ManualCardError.NoModel -> "Cards need the card model set up: Settings > WhatsApp > Topic cards"
    ManualCardError.Off -> "Chat search is off, so cards cannot be written."
    ManualCardError.Unavailable -> "The card store cannot be used right now. Try again in a moment."
    ManualCardError.Finished, ManualCardError.NotManual, ManualCardError.Other -> "The request could not be sent. Try again."
}

/** What a refused delete says; a 404 means it is already gone, which the caller treats as done. */
fun deleteErrorText(e: ManualCardError): String =
    if (e == ManualCardError.NotManual) "Only a card you asked for can be deleted." else "The card could not be deleted. Try again."

/** The snackbar text for a job that finished while its sheet was closed. */
fun readyText(job: WaManualJob): String = when (job.state) {
    "done" -> "Your card is ready"
    "failed" -> jobFailureText(job.error)
    else -> "Your card was cancelled"
}

/** The messages a card was written for, as a set to tint; blank ids are never picked. */
fun pickedSet(ids: List<String>): Set<String> = ids.filter { it.isNotBlank() }.toSet()

/** A card of the owner's own, as the card view and "Show in chat" need it. */
data class YourCardRef(
    val cardId: Long,
    val chat: String,
    val chatName: String = "",
    val title: String = "",
    val summary: String = "",
    val instruction: String = "",
    val firstTs: Long = 0,
    val lastTs: Long = 0,
    val nMsgs: Int = 0,
    val msgIds: List<String> = emptyList(),
)

/** A search result's own card as a [YourCardRef]. */
fun WaYourCard.toRef(): YourCardRef = YourCardRef(
    cardId = cardId, chat = chat, chatName = chatName, title = title.trim(), summary = summary.trim(),
    instruction = instruction.orEmpty().trim(), firstTs = start.ts, lastTs = end.ts, nMsgs = nMsgs, msgIds = msgIds,
)
