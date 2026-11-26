package com.raavivi.sysmon.ui.whatsapp

import com.raavivi.sysmon.core.model.WaRewrite
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The words and decisions of the Deep search in Conversations, with no UI: what the server read from the query
 * ("Read as"), and which readings a result met ("Matches"). Port of the web dashboard's deep search text.
 * Query text is private: nothing here logs.
 */

/** The line above the skeleton while a deep search runs. */
const val DEEP_SEARCHING = "Searching more carefully…"

/** What Deep does, for the search guide and for TalkBack on the toggle. */
const val DEEP_DESCRIPTION = "Reads time, people and what happened from your words, then looks more carefully. Slower."

/** The line shown when a deep search read nothing from the query. */
const val READ_AS_NOTHING = "Read as: just the words"

private val ActLabels = mapOf(
    "argu" to "arguing",
    "flirt" to "flirting",
    "vent" to "venting",
    "plan" to "planning",
    "decid" to "deciding",
    "jok" to "joking",
    "comfort" to "comforting",
    "apolog" to "apologising",
)

/** An act stem as a word ("argu" -> "arguing"); a stem that is not known stays as it is. */
fun actLabel(stem: String): String = ActLabels[stem.trim().lowercase(Locale.ROOT)] ?: stem

private val DayFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val DayMonthFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DayOnlyFmt = DateTimeFormatter.ofPattern("d", Locale.ENGLISH)

/**
 * A range of unix seconds (inclusive) as device-local days: "5 Dec 2025", "1–31 Dec 2025", "3 Jan – 4 Mar 2025"
 * or "20 Dec 2025 – 3 Jan 2026". Empty when there is no range (both 0, or the end before the start).
 */
fun deepRangeText(from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if ((from == 0L && to == 0L) || to < from) return ""
    val a = Instant.ofEpochSecond(from).atZone(zone).toLocalDate()
    val b = Instant.ofEpochSecond(to).atZone(zone).toLocalDate()
    return when {
        a == b -> a.format(DayFmt)
        a.year == b.year && a.month == b.month -> "${a.format(DayOnlyFmt)}–${b.format(DayFmt)}"
        a.year == b.year -> "${a.format(DayMonthFmt)} – ${b.format(DayFmt)}"
        else -> "${a.format(DayFmt)} – ${b.format(DayFmt)}"
    }
}

/** The time reading: the phrase and its days, either alone when the other is missing, "a time" when both are. */
private fun timeReading(r: WaRewrite, zone: ZoneId, joiner: String): String? {
    val t = r.time ?: return null
    val phrase = t.phrase.trim()
    val range = deepRangeText(t.from, t.to, zone)
    return when {
        phrase.isNotEmpty() && range.isNotEmpty() -> "$phrase$joiner$range"
        phrase.isNotEmpty() -> phrase
        range.isNotEmpty() -> range
        else -> "a time"
    }
}

private fun personReading(name: String, chats: Int, many: String): String? {
    val n = name.trim()
    if (n.isEmpty()) return null
    return if (chats > 1) "$n$many" else n
}

private fun actsOf(r: WaRewrite): List<String> = r.acts.map { it.trim() }.filter { it.isNotEmpty() }.map(::actLabel)

private fun expansionsOf(r: WaRewrite): List<String> = r.expansions.map { it.trim() }.filter { it.isNotEmpty() }

/** The tags of the "Read as" block: the time, each act, each person, then "also: a, b" when words were added. */
fun readAsTags(r: WaRewrite, zone: ZoneId = ZoneId.systemDefault()): List<String> = buildList {
    timeReading(r, zone, " · ")?.let { add(it) }
    addAll(actsOf(r))
    for (p in r.people) personReading(p.name, p.chats, " (${p.chats} chats)")?.let { add(it) }
    expansionsOf(r).takeIf { it.isNotEmpty() }?.let { add("also: ${it.joinToString(", ")}") }
}

/** The imagined text of a model reading, trimmed; null when there is none. */
fun readAsImagined(r: WaRewrite): String? = r.imagined?.trim()?.takeIf { it.isNotEmpty() }

/** The whole "Read as" block as one sentence for TalkBack. */
fun readAsDescription(r: WaRewrite, zone: ZoneId = ZoneId.systemDefault()): String {
    val parts = buildList {
        timeReading(r, zone, ", ")?.let { add(it) }
        addAll(actsOf(r))
        for (p in r.people) personReading(p.name, p.chats, ", ${p.chats} chats")?.let { add(it) }
        expansionsOf(r).takeIf { it.isNotEmpty() }?.let { add("also: ${it.joinToString(", ")}") }
        readAsImagined(r)?.let { add("imagined: $it") }
    }
    return if (parts.isEmpty()) READ_AS_NOTHING else "Read as: ${parts.joinToString("; ")}"
}

/** "Matches: when · who · what happened" for the readings a result met, in that order; null when none. */
fun matchedLine(matched: List<String>): String? {
    val words = listOf("time" to "when", "people" to "who", "acts" to "what happened")
        .filter { (key, _) -> key in matched }
        .map { it.second }
    return if (words.isEmpty()) null else "Matches: ${words.joinToString(" · ")}"
}
