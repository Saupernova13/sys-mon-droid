package com.raavivi.sysmon.ui.whatsapp

import com.raavivi.sysmon.core.model.WaFacetCount

/**
 * The decisions behind the theme chips over the Conversations results, with no UI: the selection and its cap,
 * the chip order, the texts. Theme names come from the owner's cards and are private: nothing here logs.
 */

/** The server filters on at most this many themes at once. */
const val MAX_THEMES = 5

/** How many of a result's own themes its line shows. */
const val THEMES_ON_A_CARD = 3

/** The note shown when a sixth theme is pressed. */
const val THEME_CAP_NOTE = "Up to 5 themes at once"

/** The selection after a press: [capped] when a sixth theme was refused (the selection is then unchanged). */
data class ThemeToggle(val selected: List<String>, val capped: Boolean = false)

/** One chip: the theme, how many conversations carry it, and whether it is on. */
data class ThemeChip(val name: String, val count: Int, val selected: Boolean)

/** Press [name]: it leaves the selection when in it (any case), else joins it, up to [MAX_THEMES]. */
fun toggleTheme(selected: List<String>, name: String): ThemeToggle {
    val key = name.trim()
    if (key.isEmpty()) return ThemeToggle(selected)
    if (selected.any { it.equals(key, ignoreCase = true) }) {
        return ThemeToggle(selected.filterNot { it.equals(key, ignoreCase = true) })
    }
    if (selected.size >= MAX_THEMES) return ThemeToggle(selected, capped = true)
    return ThemeToggle(selected + key)
}

/** The server's counts without blank or repeated (any case) names and non-positive counts, in its order. */
fun cleanThemeCounts(counts: List<WaFacetCount>): List<WaFacetCount> {
    val seen = HashSet<String>()
    return counts.filter { it.name.isNotBlank() && it.count > 0 && seen.add(it.name.trim().lowercase()) }
}

/**
 * The chips in row order: the selected ones first (in the order they were picked), each with its count; a
 * selected theme the server no longer lists stays with count 0 so it can be turned off; then the rest in the
 * server's order.
 */
fun themeChips(selected: List<String>, counts: List<WaFacetCount>): List<ThemeChip> {
    fun countOf(name: String) = counts.firstOrNull { it.name.equals(name, ignoreCase = true) }?.count ?: 0
    val on = selected.map { ThemeChip(it, countOf(it), selected = true) }
    val rest = counts.filter { c -> selected.none { it.equals(c.name, ignoreCase = true) } }
        .map { ThemeChip(it.name, it.count, selected = false) }
    return on + rest
}

/** What a screen reader says for a chip: "planning, 7 conversations". */
fun themeChipLabel(chip: ThemeChip): String =
    "${chip.name}, ${chip.count} ${if (chip.count == 1) "conversation" else "conversations"}"

/** A result's own themes as one muted line: up to [THEMES_ON_A_CARD] joined by " · "; null when it has none. */
fun themesLine(facets: List<String>): String? =
    facets.map { it.trim() }.filter { it.isNotEmpty() }.take(THEMES_ON_A_CARD).joinToString(" · ").ifEmpty { null }
