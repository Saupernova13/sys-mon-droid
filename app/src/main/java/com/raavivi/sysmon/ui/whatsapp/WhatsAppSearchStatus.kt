package com.raavivi.sysmon.ui.whatsapp

import com.raavivi.sysmon.core.model.WaCardsStatus
import com.raavivi.sysmon.core.model.WaChatSearchStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.NumberFormat
import java.time.ZoneId
import java.util.Locale

/**
 * The Search view's index line and topic-cards line, as pure decisions over the chat-search status: whether
 * each shows, what it says, which button it offers and whether the status is worth polling. Port of the web
 * whatsapp-search-index-state.js and whatsapp-search-cards-state.js, texts included.
 */

/** How often the status is read while a run goes and the Search view is open. */
const val STATUS_POLL_MS = 3000L

/** What a line shows; [button] is empty when it offers none. */
data class LineView(val visible: Boolean, val running: Boolean = false, val text: String = "", val button: String = "") {
    companion object { val Hidden = LineView(visible = false) }
}

private fun num(n: Number): String = NumberFormat.getIntegerInstance(Locale.US).format(n)

private fun voiceLeft(st: WaChatSearchStatus): String {
    val n = st.voiceNotes?.pending ?: return ""
    if (n <= 0) return ""
    return "${num(n)} voice note${if (n == 1) "" else "s"} left"
}

/** The progress under "Updating the index…": messages indexed of the total, voice notes left. */
fun progressText(st: WaChatSearchStatus): String {
    val parts = mutableListOf<String>()
    val done = st.messagesIndexed
    val total = st.messagesTotal
    if (done != null && total != null && total > 0) parts += "${num(minOf(done, total))} / ${num(total)} messages"
    voiceLeft(st).takeIf { it.isNotEmpty() }?.let { parts += it }
    return parts.joinToString(" · ")
}

/** The index is updated only on request: the status says automatic indexing is off. */
fun isManual(st: WaChatSearchStatus?): Boolean = st != null && st.enabled && st.auto == false

/**
 * The index line. [inConv]: the Search view is where the index matters, that is in Conversations mode or
 * when there is no conversation mode yet (an index never built is exactly when the button is needed).
 */
fun indexView(st: WaChatSearchStatus?, inConv: Boolean): LineView {
    if (st == null || !isManual(st) || !inConv) return LineView.Hidden
    if (st.runRequested) {
        val progress = progressText(st)
        return LineView(true, running = true, text = if (progress.isNotEmpty()) "Updating the index… $progress" else "Updating the index…")
    }
    return LineView(true, running = false, text = "Search index ·", button = "Update")
}

/** One poller for both lines: an index run or a topic-card run is going while the view is open. */
fun shouldPollAny(st: WaChatSearchStatus?, viewOpen: Boolean): Boolean =
    viewOpen && st != null && ((isManual(st) && st.runRequested) || (st.enabled && st.cards?.requested == true))

// -- topic cards ----------------------------------------------------------------------------------

/** What a failed attempt is waiting on, in plain words (never the raw kind). */
private fun waitingText(err: JsonElement?): String {
    val kind = when (err) {
        null, JsonNull -> return ""
        is JsonPrimitive -> err.content
        is JsonObject -> (err["kind"] as? JsonPrimitive)?.content.orEmpty()
        else -> ""
    }
    return if (kind == "server_unavailable") " · waiting (model server not available)" else " · waiting (a problem; retrying)"
}

/** "1,234 done · 15,113 left", leaving out a part whose number is missing. */
private fun counts(cards: WaCardsStatus): String {
    val parts = mutableListOf<String>()
    cards.done?.let { parts += "${num(it)} done" }
    cards.eligiblePending?.let { parts += "${num(it)} left" }
    return parts.joinToString(" · ")
}

private fun clockOf(unix: Double?, zone: ZoneId): String = if (unix == null || !unix.isFinite()) "" else waClock(unix.toLong(), zone)

private fun withCounts(head: String, progress: String) = if (progress.isNotEmpty()) "$head · $progress" else head

private fun runningText(cards: WaCardsStatus, progress: String, zone: ZoneId): String = when (cards.mode) {
    "break" -> {
        val at = clockOf(cards.breakUntil, zone)
        withCounts(if (at.isNotEmpty()) "Topic cards on a break until $at" else "Topic cards on a break", progress)
    }
    "paused_game" -> withCounts("Topic cards paused: a game is running", progress)
    "waiting_gpu" -> withCounts("Topic cards waiting for the GPU", progress)
    else -> (if (progress.isNotEmpty()) "Writing topic cards… $progress" else "Writing topic cards…") + waitingText(cards.lastError)
}

/**
 * The topic-cards line: only when cards are configured and [inConv]. `running` is a manual run or the night
 * window writing; either one is stopped with the button.
 */
fun cardsView(st: WaChatSearchStatus?, inConv: Boolean, zone: ZoneId = ZoneId.systemDefault()): LineView {
    val cards = st?.takeIf { it.enabled }?.cards
    if (cards == null || !cards.configured || !inConv) return LineView.Hidden
    val progress = counts(cards)
    if (cards.requested || cards.auto) return LineView(true, running = true, text = runningText(cards, progress, zone), button = "Stop")
    if (cards.eligiblePending == 0) {
        val text = cards.done?.let { "Topic cards · all ${num(it)} done" } ?: "Topic cards · all done"
        return LineView(true, text = text)
    }
    val next = clockOf(cards.nextWindowStart, zone)
    if (cards.mode == "waiting_window" && next.isNotEmpty()) {
        return LineView(true, text = withCounts("Topic cards · next at $next", progress), button = "Run")
    }
    return LineView(true, text = if (progress.isNotEmpty()) "Topic cards · $progress" else "Topic cards ·", button = "Run")
}
