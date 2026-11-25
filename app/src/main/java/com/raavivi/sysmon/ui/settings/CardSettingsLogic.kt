package com.raavivi.sysmon.ui.settings

import com.raavivi.sysmon.core.model.WaCardChat
import com.raavivi.sysmon.core.model.WaCardSettings
import com.raavivi.sysmon.core.model.WaCardSettingsPatch
import com.raavivi.sysmon.core.model.WaChat
import java.util.Locale
import kotlin.math.ceil

/**
 * The decisions behind the Card settings sheet, with no UI: the editable draft, the patch that holds only
 * what changed, validation, and the texts (next start, what adding a chat would take). Chat names are private:
 * nothing here logs.
 */

/** The server clamps the smallest topic to this range; the stepper keeps to it. */
const val CARD_MIN_MSGS_LOW = 2
const val CARD_MIN_MSGS_HIGH = 100_000

/** The server lists at most this many chats. */
const val CARD_MAX_CHATS = 50

/** Seconds one card takes to write, as measured on the card server; the estimate is built on it. */
const val CARD_SECONDS_EACH = 16

/** The window shown when the settings have none yet: 03:00 to 06:00, in minutes since midnight. */
const val DEFAULT_FROM = 3 * 60
const val DEFAULT_TO = 6 * 60

/** "03:00" for [minutes] since midnight. */
fun clockText(minutes: Int): String = String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

/** Minutes since midnight of an `HH:MM` text, or null when it is not one. */
fun parseClock(text: String): Int? {
    val m = Regex("""(\d{1,2}):(\d{2})""").matchEntire(text.trim()) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    return if (h in 0..23 && min in 0..59) h * 60 + min else null
}

/** `from` and `to` (minutes since midnight) of a `HH:MM-HH:MM` window, or null for an empty or unreadable one. */
fun parseWindow(window: String): Pair<Int, Int>? {
    val parts = window.trim().split("-")
    if (parts.size != 2) return null
    val from = parseClock(parts[0]) ?: return null
    val to = parseClock(parts[1]) ?: return null
    return from to to
}

/** What the sheet edits. [night] off means no window; [from]/[to] are kept so switching it on again restores them. */
data class CardDraft(
    val night: Boolean,
    val from: Int,
    val to: Int,
    val chats: List<WaCardChat>,
    val minMsgs: Int,
) {
    /** The window as the server stores it: `HH:MM-HH:MM`, or empty with the switch off. */
    val window: String get() = if (night) "${clockText(from)}-${clockText(to)}" else ""
}

/** The draft that starts as the saved [s]. */
fun draftOf(s: WaCardSettings): CardDraft {
    val w = parseWindow(s.window)
    return CardDraft(
        night = w != null,
        from = w?.first ?: DEFAULT_FROM,
        to = w?.second ?: DEFAULT_TO,
        chats = s.chats,
        minMsgs = clampMinMsgs(s.minMsgs),
    )
}

fun clampMinMsgs(n: Int): Int = n.coerceIn(CARD_MIN_MSGS_LOW, CARD_MIN_MSGS_HIGH)

/** The stepper's next value: [delta] more or fewer, kept inside the range. */
fun stepMinMsgs(current: Int, delta: Int): Int = clampMinMsgs(current + delta)

/** The patch holding only the fields of [draft] that differ from [saved]; all-null when nothing changed. */
fun patchOf(saved: WaCardSettings, draft: CardDraft): WaCardSettingsPatch {
    val savedWindow = if (parseWindow(saved.window) != null) saved.window else ""
    return WaCardSettingsPatch(
        window = draft.window.takeIf { it != savedWindow },
        chats = draft.chats.map { it.jid }.takeIf { it != saved.chats.map { c -> c.jid } },
        minMsgs = draft.minMsgs.takeIf { it != saved.minMsgs },
    )
}

fun WaCardSettingsPatch.isEmpty(): Boolean = window == null && chats == null && minMsgs == null

/** Why [draft] cannot be saved, or null when it can. */
fun draftProblem(draft: CardDraft): String? = when {
    draft.night && draft.from == draft.to -> "From and To must be different times"
    draft.chats.size > CARD_MAX_CHATS -> "At most $CARD_MAX_CHATS chats may be carded"
    draft.minMsgs !in CARD_MIN_MSGS_LOW..CARD_MIN_MSGS_HIGH -> "Minimum messages must be $CARD_MIN_MSGS_LOW to $CARD_MIN_MSGS_HIGH"
    else -> null
}

/** Save is offered once something changed and the draft is valid. */
fun canSave(saved: WaCardSettings, draft: CardDraft): Boolean = !patchOf(saved, draft).isEmpty() && draftProblem(draft) == null

/** The line under the night switch: when the window runs, or that cards only come from a manual run. */
fun nightHint(draft: CardDraft): String =
    if (draft.night) "Writes each night from ${clockText(draft.from)} to ${clockText(draft.to)}"
    else "Cards are only written when you tap Run."

fun addChat(draft: CardDraft, chat: WaCardChat): CardDraft =
    if (draft.chats.any { it.jid == chat.jid }) draft else draft.copy(chats = draft.chats + chat)

fun removeChat(draft: CardDraft, jid: String): CardDraft = draft.copy(chats = draft.chats.filter { it.jid != jid })

/** "9 / 14 topics carded". */
fun chatProgress(chat: WaCardChat): String =
    "${chat.carded} / ${chat.topics} ${if (chat.topics == 1) "topic" else "topics"} carded"

/** Topics of [chat] still without a card. */
fun topicsToCard(chat: WaCardChat): Int = (chat.topics - chat.carded).coerceAtLeast(0)

/** Hours of writing for [topics] cards, rounded up to the next half hour. */
fun writingHours(topics: Int): Double = ceil(topics * CARD_SECONDS_EACH / 3600.0 * 2) / 2

/** "2" or "0.5": no trailing ".0". */
private fun hoursText(h: Double): String = if (h % 1.0 == 0.0) h.toInt().toString() else h.toString()

/** What adding [chat] (named [name]) would take: its topics without a card, and the overnight time to write them. */
fun addEstimate(name: String, chat: WaCardChat): String {
    val n = topicsToCard(chat)
    if (n == 0) return "$name: nothing to card yet"
    return "$name: ~$n ${if (n == 1) "topic" else "topics"} to card, about ${hoursText(writingHours(n))} h of overnight writing"
}

/** The digits of [text]; the jid's user part is the phone number. */
private fun digitsOf(text: String): String = text.filter(Char::isDigit)

/**
 * The chats the picker offers for [query], in the order given, leaving out the [listed] jids. A chat matches when
 * its name holds every word of the query (any case), or, when the query has at least 4 digits, when the digits of
 * its jid hold the query's digits (or those without leading zeros, so "082 123 4567" finds 27821234567).
 */
fun pickerChats(chats: List<WaChat>, query: String, listed: Set<String> = emptySet()): List<WaChat> {
    val open = chats.filter { it.jid !in listed }
    val q = query.trim()
    if (q.isEmpty()) return open
    val words = q.split(Regex("""\s+""")).filter { it.isNotEmpty() }
    val digits = digitsOf(q)
    val stripped = digits.trimStart('0')
    val byPhone = digits.length >= 4
    return open.filter { chat ->
        words.all { chat.name.contains(it, ignoreCase = true) } ||
            (byPhone && digitsOf(chat.jid.substringBefore('@')).let { d ->
                d.contains(digits) || (stripped.isNotEmpty() && d.contains(stripped))
            })
    }
}

/** What the screen shows for a failed read: a 404 means chat search is off on the server, not an error. */
fun isSearchOff(code: Int?): Boolean = code == 404
