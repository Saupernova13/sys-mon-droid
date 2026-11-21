package com.raavivi.sysmon.ui.whatsapp

import com.raavivi.sysmon.core.model.WaConvResult
import com.raavivi.sysmon.core.model.WaPickHit
import com.raavivi.sysmon.core.model.WaPickReq
import com.raavivi.sysmon.core.model.WaSearchRow
import java.time.Instant
import java.time.ZoneId

/**
 * The decisions behind the WhatsApp searches, with no UI: how hits group under their chats, which filter
 * chips are on, what the in-chat counter says and which stepping buttons are live, plus the text helpers
 * the result rows use. A port of the web dashboard's whatsapp-search-state.js / -convs.js / -global.js.
 * Query text and snippets are private: nothing here logs.
 */

/** Characters the chat-name filter needs before it offers "Search messages for ...". */
const val SEARCH_MIN_CHARS = 2

/** Hits asked for per page (the server clamps to 100). */
const val SEARCH_PAGE = 100

/** Conversations asked for per query. */
const val CONV_LIMIT = 20

/** Quiet time after the last keystroke before a search runs. */
const val SEARCH_DEBOUNCE_MS = 250L

/** Rows a chat's group shows before "N more in this chat". */
const val GROUP_PREVIEW_ROWS = 3

/** Hit pages read to find the one a Search-view tap opened (web `MAX_SEEK_PAGES`). */
const val MAX_SEEK_PAGES = 10

/** Search mode of the Search view. */
enum class SearchMode { CONVERSATIONS, MESSAGES }

// -- grouping -------------------------------------------------------------------------------------

/** The hits of one chat, newest first as the server returned them. */
data class HitGroup(val chat: String, val hits: List<WaSearchRow>)

/** Group hits by chat in encounter order. */
fun groupHits(hits: List<WaSearchRow>): List<HitGroup> {
    val byChat = LinkedHashMap<String, MutableList<WaSearchRow>>()
    for (h in hits) byChat.getOrPut(h.chatJid) { mutableListOf() } += h
    return byChat.map { (chat, list) -> HitGroup(chat, list) }
}

/** The rows of a group on screen (all once expanded) and how many are folded away. */
data class GroupWindow(val shown: List<WaSearchRow>, val rest: Int)

fun groupWindow(group: HitGroup, expanded: Boolean): GroupWindow {
    val shown = if (expanded) group.hits else group.hits.take(GROUP_PREVIEW_ROWS)
    return GroupWindow(shown, group.hits.size - shown.size)
}

// -- filter chips ---------------------------------------------------------------------------------

/** The chips of the Messages mode: one sender chip and one type chip may be on at once. */
enum class SearchChip(val group: ChipGroup, val value: String, val label: String) {
    MINE(ChipGroup.SENDER, "mine", "Mine"),
    THEIRS(ChipGroup.SENDER, "theirs", "Theirs"),
    PHOTOS(ChipGroup.MEDIA, "image", "Photos"),
    VIDEOS(ChipGroup.MEDIA, "video", "Videos"),
    VOICE(ChipGroup.MEDIA, "audio", "Voice notes"),
    DOCUMENTS(ChipGroup.MEDIA, "document", "Documents"),
}

enum class ChipGroup { SENDER, MEDIA }

/** Which sender and which type chip are on (null: none). */
data class ChipSel(val sender: SearchChip? = null, val media: SearchChip? = null) {
    val none: Boolean get() = sender == null && media == null
    fun isOn(chip: SearchChip): Boolean = sender == chip || media == chip
}

/** The selection after pressing [chip]: pressing the one that is on turns it off; null is "All" and clears both. */
fun pressChip(sel: ChipSel, chip: SearchChip?): ChipSel = when {
    chip == null -> ChipSel()
    chip.group == ChipGroup.SENDER -> sel.copy(sender = if (sel.sender == chip) null else chip)
    else -> sel.copy(media = if (sel.media == chip) null else chip)
}

/** The API filter params of a chip selection. */
data class ChipFilters(val fromMe: Boolean?, val media: String?)

fun chipFilters(sel: ChipSel): ChipFilters = ChipFilters(
    fromMe = when (sel.sender) { SearchChip.MINE -> true; SearchChip.THEIRS -> false; else -> null },
    media = sel.media?.value,
)

// -- the in-chat counter and stepping -------------------------------------------------------------

/** The counter: "12 hits" before one is selected, "3/12" after, "1000+" when the server capped it; blank with none. */
fun countText(total: Int, capped: Boolean, loaded: Int, sel: Int): String {
    val shownTotal = if (capped) "1000+" else total.toString()
    if (loaded == 0) return if (total > 0) "$shownTotal hits" else ""
    return if (sel < 0) "$shownTotal hits" else "${sel + 1}/$shownTotal"
}

/** The index a step of [d] from [sel] aims at, or null when there is nowhere to go. */
fun stepTarget(sel: Int, d: Int, loaded: Int, hasNext: Boolean): Int? {
    val target = sel + d
    if (loaded == 0 || target < 0) return null
    if (target >= loaded && !hasNext) return null
    return target
}

/** Which stepping buttons are off. Hits run newest first, so "newer" is the lower index. */
data class StepButtons(val newerOff: Boolean, val olderOff: Boolean)

fun stepButtons(sel: Int, loaded: Int, hasNext: Boolean): StepButtons =
    StepButtons(newerOff = sel <= 0, olderOff = loaded == 0 || (sel >= loaded - 1 && !hasNext))

// -- text helpers ---------------------------------------------------------------------------------

/** A half-open character range of a string. */
data class Span(val start: Int, val end: Int)

/** A snippet with the server's `\u0002`..`\u0003` markers taken out and the marked [marks] ranges kept. */
data class Snippet(val text: String, val marks: List<Span>)

private const val MARK_OPEN = '\u0002'
private const val MARK_CLOSE = '\u0003'

fun parseSnippet(raw: String): Snippet {
    val sb = StringBuilder()
    val marks = mutableListOf<Span>()
    var open = -1
    for (ch in raw) {
        when (ch) {
            MARK_OPEN -> open = sb.length
            MARK_CLOSE -> {
                if (open >= 0 && sb.length > open) marks += Span(open, sb.length)
                open = -1
            }
            else -> sb.append(ch)
        }
    }
    return Snippet(sb.toString(), marks)
}

/** The ranges of [text] holding the content words (3+ letters or digits) of [q], for a conversation preview line. */
fun markQueryWords(text: String, q: String): List<Span> {
    val words = q.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
    if (words.isEmpty() || text.isEmpty()) return emptyList()
    val re = Regex("\\b(" + words.joinToString("|") { Regex.escape(it) } + ")\\b", RegexOption.IGNORE_CASE)
    return re.findAll(text).map { Span(it.range.first, it.range.last + 1) }.toList()
}

/** "HH:MM" of a unix time in [zone]. */
fun waClock(unix: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochSecond(unix).atZone(zone).toLocalTime()
    return "%02d:%02d".format(t.hour, t.minute)
}

/** `Yesterday · 19:02–20:10`: a conversation's range in its card header. */
fun convRange(startTs: Long, endTs: Long, nowSeconds: Double, zone: ZoneId = ZoneId.systemDefault()): String =
    "${waDayLabel(startTs.toDouble(), nowSeconds, zone)} · ${waClock(startTs, zone)}–${waClock(endTs, zone)}"

/** `Topic 19:02–20:10 · 14 messages`: the parent topic of a sub-topic result. */
fun convCrumb(startTs: Long, endTs: Long, nMsgs: Int, zone: ZoneId = ZoneId.systemDefault()): String =
    "Topic ${waClock(startTs, zone)}–${waClock(endTs, zone)} · $nMsgs messages"

// -- picks ----------------------------------------------------------------------------------------

/** The `found` verdict for [conv], the [rank]-th (1-based) of [shown] results of the query [q]. */
fun foundPick(q: String, conv: WaConvResult, rank: Int, shown: Int): WaPickReq = WaPickReq(
    q = q,
    verdict = "found",
    hit = WaPickHit(conv.family, conv.startTs, conv.startRowid, conv.endTs, conv.endRowid),
    rank = rank,
    shown = shown,
)

/** The `not_here` verdict: none of the [shown] results was the conversation meant. */
fun notHerePick(q: String, shown: Int): WaPickReq = WaPickReq(q = q, verdict = "not_here", shown = shown)
